package kim.mingyo.browserssh.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import kim.mingyo.browserssh.config.TerminalProperties;
import kim.mingyo.browserssh.core.TerminalClientMessage;
import kim.mingyo.browserssh.core.TerminalProtocol;
import kim.mingyo.browserssh.core.TerminalSessionState;
import kim.mingyo.browserssh.terminal.LocalTerminalService;
import kim.mingyo.browserssh.terminal.TerminalProcess;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(TerminalWebSocketHandler.class);
    private static final Duration MAX_WATCH_INTERVAL = Duration.ofSeconds(5);

    private final LocalTerminalService terminalService;
    private final ObjectMapper objectMapper;
    private final TerminalProperties properties;
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService watchers = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "browser-ssh-session-watch");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, ActiveTerminalSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger activeSessionCount = new AtomicInteger();

    public TerminalWebSocketHandler(
            LocalTerminalService terminalService,
            ObjectMapper objectMapper,
            TerminalProperties properties
    ) {
        this.terminalService = terminalService;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String email = authenticatedEmail(session.getPrincipal());
        if (email == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Authentication required"));
            return;
        }
        if (activeSessionCount.incrementAndGet() > properties.maxConcurrentSessions()) {
            activeSessionCount.decrementAndGet();
            send(session, "error", "Terminal session limit reached.");
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Concurrent session limit"));
            return;
        }

        ActiveTerminalSession active = null;
        try {
            TerminalProcess process = terminalService.start();
            active = new ActiveTerminalSession(
                    process,
                    properties.idleTimeout(),
                    properties.maxSessionDuration(),
                    properties.maxMessagesPerSecond()
            );
            ActiveTerminalSession started = active;
            sessions.put(session.getId(), started);
            started.setReader(readers.submit(() -> streamOutput(session, started)));
            long periodMillis = started.state().watchPeriodMillis(MAX_WATCH_INTERVAL);
            started.setWatcher(watchers.scheduleAtFixedRate(
                    () -> closeExpiredSession(session, started),
                    periodMillis,
                    periodMillis,
                    TimeUnit.MILLISECONDS
            ));
            log.info("Browser terminal opened: email={}, client={}", email, clientAddress(session));
            send(session, "status", "Connected.");
        } catch (RuntimeException ex) {
            sessions.remove(session.getId());
            if (active != null) {
                active.close();
            }
            activeSessionCount.decrementAndGet();
            log.warn("Could not open browser terminal: email={}", email, ex);
            send(session, "error", "Could not open terminal.");
            session.close(CloseStatus.SERVER_ERROR.withReason("Could not open terminal"));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        ActiveTerminalSession active = sessions.get(session.getId());
        if (active == null) {
            return;
        }
        if (!active.state().allowMessage(System.currentTimeMillis())) {
            closeForPolicyViolation(session, "Terminal message rate exceeded.");
            return;
        }

        TerminalClientMessage payload;
        try {
            payload = TerminalProtocol.parse(objectMapper, message.getPayload());
        } catch (IOException ex) {
            closeForPolicyViolation(session, "Invalid terminal message.");
            return;
        }
        if (payload.type() == TerminalClientMessage.Type.HEARTBEAT) {
            send(session, "pong", "ok");
            return;
        }
        if (payload.type() == TerminalClientMessage.Type.RESIZE) {
            if (payload.columns() > 0 && payload.rows() > 0) {
                active.process().resize(payload.columns(), payload.rows());
                active.state().markActivity();
            }
            return;
        }
        if (payload.type() != TerminalClientMessage.Type.INPUT) {
            return;
        }
        String input = payload.data();
        if (input.length() > properties.maxInputCharacters()) {
            closeForPolicyViolation(session, "Terminal input is too large.");
            return;
        }
        if (!input.isEmpty()) {
            active.state().markActivity();
            active.process().write(input);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        closeActiveSession(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closeActiveSession(session);
    }

    @PreDestroy
    public void stop() {
        sessions.values().forEach(ActiveTerminalSession::close);
        sessions.clear();
        activeSessionCount.set(0);
        readers.shutdownNow();
        watchers.shutdownNow();
    }

    private void streamOutput(WebSocketSession session, ActiveTerminalSession active) {
        char[] buffer = new char[4096];
        try (InputStreamReader reader = new InputStreamReader(active.process().stdout(), StandardCharsets.UTF_8)) {
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                active.state().markActivity();
                send(session, "output", new String(buffer, 0, read));
            }
            send(session, "status", "Terminal session closed.");
        } catch (IOException ex) {
            if (session.isOpen()) {
                log.debug("Terminal output stream closed unexpectedly.", ex);
                send(session, "error", "Terminal stream closed.");
            }
        } finally {
            try {
                session.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void closeExpiredSession(WebSocketSession session, ActiveTerminalSession active) {
        if (!session.isOpen()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (active.state().maximumDurationReached(now)) {
            send(session, "status", "Maximum terminal session duration reached.");
            closeSession(session, CloseStatus.NORMAL.withReason("Maximum session duration"));
        } else if (active.state().isIdle(now)) {
            send(session, "status", "Terminal closed after inactivity.");
            closeSession(session, CloseStatus.NORMAL.withReason("Terminal idle timeout"));
        }
    }

    private void closeForPolicyViolation(WebSocketSession session, String message) {
        send(session, "error", message);
        closeSession(session, CloseStatus.POLICY_VIOLATION.withReason(message));
    }

    private void closeSession(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException ignored) {
        } finally {
            closeActiveSession(session);
        }
    }

    private void closeActiveSession(WebSocketSession session) {
        ActiveTerminalSession active = sessions.remove(session.getId());
        if (active == null) {
            return;
        }
        active.close();
        activeSessionCount.decrementAndGet();
        log.info("Browser terminal closed: principal={}", principalName(session));
    }

    private void send(WebSocketSession session, String type, String data) {
        if (!session.isOpen()) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(TerminalProtocol.serverMessage(type, data));
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static String authenticatedEmail(Principal principal) {
        if (principal instanceof Authentication authentication && authentication.isAuthenticated()) {
            return authentication.getName();
        }
        return null;
    }

    private static String principalName(WebSocketSession session) {
        Principal principal = session.getPrincipal();
        return principal == null ? "unknown" : principal.getName();
    }

    private static String clientAddress(WebSocketSession session) {
        String forwarded = session.getHandshakeHeaders().getFirst("CF-Connecting-IP");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded;
        }
        return session.getRemoteAddress() == null ? "unknown" : session.getRemoteAddress().getHostString();
    }

    private static class ActiveTerminalSession {
        private final TerminalProcess process;
        private final TerminalSessionState state;
        private volatile Future<?> reader;
        private volatile ScheduledFuture<?> watcher;

        ActiveTerminalSession(
                TerminalProcess process,
                Duration idleTimeout,
                Duration maximumDuration,
                int maxMessagesPerSecond
        ) {
            this.process = process;
            this.state = TerminalSessionState.open(idleTimeout, maximumDuration, maxMessagesPerSecond);
        }

        TerminalProcess process() {
            return process;
        }

        TerminalSessionState state() {
            return state;
        }

        void setReader(Future<?> reader) {
            this.reader = reader;
        }

        void setWatcher(ScheduledFuture<?> watcher) {
            this.watcher = watcher;
        }

        void close() {
            if (watcher != null) {
                watcher.cancel(false);
            }
            if (reader != null) {
                reader.cancel(true);
            }
            process.close();
        }
    }
}
