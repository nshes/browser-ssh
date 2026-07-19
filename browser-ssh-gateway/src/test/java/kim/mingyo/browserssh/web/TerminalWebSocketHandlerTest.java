package kim.mingyo.browserssh.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import kim.mingyo.browserssh.config.TerminalProperties;
import kim.mingyo.browserssh.terminal.LocalTerminalService;
import kim.mingyo.browserssh.terminal.TerminalProcess;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TerminalWebSocketHandlerTest {
    private final LocalTerminalService terminalService = mock(LocalTerminalService.class);
    private TerminalWebSocketHandler handler;

    @AfterEach
    void stopHandler() {
        if (handler != null) {
            handler.stop();
        }
    }

    @Test
    void heartbeatRepliesWithoutWritingToShell() throws Exception {
        TerminalProcess process = prepareHandler();
        WebSocketSession session = session("session-1", true);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"heartbeat\"}"));

        verify(process, never()).write(anyString());
        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(messages.capture());
        assertThat(messages.getAllValues())
                .extracting(TextMessage::getPayload)
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"pong\""));
    }

    @Test
    void inputIsForwardedToLocalShell() throws Exception {
        TerminalProcess process = prepareHandler();
        WebSocketSession session = session("session-1", true);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"input\",\"data\":\"id\\n\"}"));

        verify(process).write("id\n");
    }

    @Test
    void rejectsUnauthenticatedWebSocket() throws Exception {
        prepareHandler();
        WebSocketSession session = session("session-1", false);

        handler.afterConnectionEstablished(session);

        verify(terminalService, never()).start();
        verify(session).close(any(CloseStatus.class));
    }

    @Test
    void allowsTwoConcurrentSessionsAndRejectsThird() throws Exception {
        prepareHandler();
        WebSocketSession first = session("session-1", true);
        WebSocketSession second = session("session-2", true);
        WebSocketSession third = session("session-3", true);
        handler.afterConnectionEstablished(first);
        handler.afterConnectionEstablished(second);
        handler.afterConnectionEstablished(third);

        verify(terminalService, times(2)).start();
        verify(first, never()).close(any(CloseStatus.class));
        verify(second, never()).close(any(CloseStatus.class));
        verify(third).close(any(CloseStatus.class));
    }

    private TerminalProcess prepareHandler() {
        TerminalProperties properties = new TerminalProperties(
                "/bin/bash",
                Path.of("/tmp"),
                "test-target",
                Duration.ofMinutes(15),
                Duration.ofHours(2),
                2,
                16384,
                300
        );
        handler = new TerminalWebSocketHandler(terminalService, new ObjectMapper(), properties);
        TerminalProcess process = mock(TerminalProcess.class);
        when(process.stdout()).thenReturn(new BlockingInputStream());
        when(terminalService.start()).thenReturn(process);
        return process;
    }

    private WebSocketSession session(String id, boolean authenticated) throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        AtomicBoolean open = new AtomicBoolean(true);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenAnswer(invocation -> open.get());
        when(session.getHandshakeHeaders()).thenReturn(new HttpHeaders());
        when(session.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 12345));
        if (authenticated) {
            when(session.getPrincipal()).thenReturn(
                    UsernamePasswordAuthenticationToken.authenticated("owner@example.com", "n/a", java.util.List.of())
            );
        }
        org.mockito.Mockito.doAnswer(invocation -> {
            open.set(false);
            return null;
        }).when(session).close(any(CloseStatus.class));
        return session;
    }

    private static class BlockingInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            try {
                Thread.sleep(Long.MAX_VALUE);
                return -1;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", ex);
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return read();
        }
    }
}
