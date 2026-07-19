package kim.mingyo.browserssh.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import kim.mingyo.browserssh.config.FileTransferProperties;
import kim.mingyo.browserssh.terminal.FileTransferProcess;
import kim.mingyo.browserssh.terminal.LocalTerminalService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

@Component
public class FileTransferWebSocketHandler extends AbstractWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(FileTransferWebSocketHandler.class);
    private static final Duration TRANSFER_WAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final int ERROR_LIMIT_BYTES = 16 * 1024;
    private static final String UPLOAD_KEY = "configured-target";
    private static final TypeReference<Map<String, Object>> MESSAGE_TYPE = new TypeReference<>() {
    };

    private final LocalTerminalService terminalService;
    private final ObjectMapper objectMapper;
    private final FileTransferCapacity capacity;
    private final FileTransferProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, TransferConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, String> activeUploads = new ConcurrentHashMap<>();

    public FileTransferWebSocketHandler(
            LocalTerminalService terminalService,
            ObjectMapper objectMapper,
            FileTransferCapacity capacity,
            FileTransferProperties properties
    ) {
        this.terminalService = terminalService;
        this.objectMapper = objectMapper;
        this.capacity = capacity;
        this.properties = properties;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String email = authenticatedEmail(session);
        if (email == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Authentication required"));
            return;
        }
        if (!capacity.tryOpenConnection()) {
            sendError(session, "File transfer connection limit reached.");
            session.close(CloseStatus.POLICY_VIOLATION.withReason("File transfer connection limit"));
            return;
        }
        TransferConnection previous = connections.putIfAbsent(
                session.getId(),
                new TransferConnection(email, session.getId())
        );
        if (previous != null) {
            capacity.closeConnection();
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Duplicate file transfer connection"));
            return;
        }
        if (session instanceof StandardWebSocketSession standardSession) {
            standardSession.getNativeSession().setMaxIdleTimeout(properties.connectionIdleTimeout().toMillis());
        }
        send(session, Map.of(
                "type", "ready",
                "maxBytes", LocalTerminalService.MAX_UPLOAD_BYTES
        ));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        TransferConnection connection = connections.get(session.getId());
        if (connection == null) {
            return;
        }
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(message.getPayload(), MESSAGE_TYPE);
        } catch (IOException ex) {
            sendError(session, "Invalid file transfer request.");
            return;
        }
        Object type = payload.get("type");
        if ("upload-start".equals(type)) {
            startUpload(session, connection, payload);
        } else if ("upload-complete".equals(type)) {
            finishUpload(session, connection);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        TransferConnection connection = connections.get(session.getId());
        UploadState upload = connection == null ? null : connection.upload;
        if (upload == null) {
            sendError(session, "No upload is in progress.");
            return;
        }

        ByteBuffer payload = message.getPayload();
        int chunkBytes = payload.remaining();
        long nextReceived = upload.receivedBytes + chunkBytes;
        if (nextReceived > upload.expectedBytes || nextReceived > LocalTerminalService.MAX_UPLOAD_BYTES) {
            failTransfer(session, connection, "Upload exceeds the requested size.");
            return;
        }

        byte[] chunk = new byte[chunkBytes];
        payload.get(chunk);
        try {
            upload.process.stdin().write(chunk);
            upload.process.stdin().flush();
            upload.receivedBytes = nextReceived;
            send(session, Map.of(
                    "type", "upload-progress",
                    "receivedBytes", upload.receivedBytes,
                    "totalBytes", upload.expectedBytes
            ));
        } catch (IOException ex) {
            log.warn("Browser SSH upload stream failed: email={}", connection.email, ex);
            failTransfer(session, connection, "Upload failed.");
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        closeConnection(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closeConnection(session);
    }

    @PreDestroy
    public void stop() {
        connections.values().forEach(TransferConnection::close);
        connections.keySet().forEach(ignored -> capacity.closeConnection());
        connections.clear();
        activeUploads.clear();
        executor.shutdownNow();
    }

    private void startUpload(WebSocketSession session, TransferConnection connection, Map<String, Object> payload) {
        if (connection.operationActive()) {
            sendError(session, "Another file transfer is already in progress.");
            return;
        }
        String remotePath = stringValue(payload.get("remotePath"));
        String fileName = stringValue(payload.get("fileName"));
        long sizeBytes = longValue(payload.get("sizeBytes"), -1);
        if (activeUploads.putIfAbsent(UPLOAD_KEY, session.getId()) != null) {
            sendError(session, "Another upload is already in progress.");
            return;
        }
        try {
            FileTransferProcess process = terminalService.startUpload(remotePath, sizeBytes);
            connection.upload = new UploadState(
                    process,
                    stderrFuture(process),
                    remotePath,
                    fileName,
                    sizeBytes
            );
            send(session, Map.of(
                    "type", "upload-ready",
                    "remotePath", remotePath,
                    "totalBytes", sizeBytes
            ));
        } catch (RuntimeException ex) {
            activeUploads.remove(UPLOAD_KEY, session.getId());
            log.warn("Could not start Browser SSH upload: email={}", connection.email, ex);
            sendError(session, safeTransferMessage(ex, "Could not start upload."));
        }
    }

    private void finishUpload(WebSocketSession session, TransferConnection connection) {
        UploadState upload = connection.upload;
        if (upload == null) {
            sendError(session, "No upload is in progress.");
            return;
        }
        if (upload.receivedBytes != upload.expectedBytes) {
            failTransfer(session, connection, "Upload ended before all bytes were received.");
            return;
        }
        try {
            upload.process.stdin().close();
            int exitCode = upload.process.waitFor(TRANSFER_WAIT_TIMEOUT);
            futureText(upload.stderr);
            if (exitCode != 0) {
                log.warn("Browser SSH upload failed: email={}, exit={}", connection.email, exitCode);
                failTransfer(session, connection, "Upload failed.");
                return;
            }
            log.info("Browser SSH upload completed: email={}, extension={}, bytes={}",
                    connection.email, fileExtension(upload.fileName), upload.expectedBytes);
            send(session, Map.of(
                    "type", "upload-complete",
                    "remotePath", upload.remotePath,
                    "receivedBytes", upload.receivedBytes
            ));
            clearUpload(connection);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            failTransfer(session, connection, "Upload interrupted.");
        } catch (IOException ex) {
            log.warn("Browser SSH upload completion failed: email={}", connection.email, ex);
            failTransfer(session, connection, "Upload failed.");
        }
    }

    private void failTransfer(WebSocketSession session, TransferConnection connection, String message) {
        sendError(session, message);
        connection.close();
    }

    private void clearUpload(TransferConnection connection) {
        UploadState upload = connection.upload;
        if (upload != null) {
            activeUploads.remove(UPLOAD_KEY, connection.sessionId);
            upload.close();
            connection.upload = null;
        }
    }

    private Future<String> stderrFuture(FileTransferProcess process) {
        return executor.submit(() -> readLimited(process.stderr()));
    }

    private static String readLimited(java.io.InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() < ERROR_LIMIT_BYTES) {
                output.write(buffer, 0, Math.min(read, ERROR_LIMIT_BYTES - output.size()));
            }
        }
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String futureText(Future<String> future) {
        if (future == null) {
            return "";
        }
        try {
            return future.get();
        } catch (Exception ex) {
            return "";
        }
    }

    private void closeConnection(WebSocketSession session) {
        TransferConnection connection = connections.remove(session.getId());
        if (connection != null) {
            connection.close();
            capacity.closeConnection();
        }
    }

    private void sendError(WebSocketSession session, String message) {
        send(session, Map.of("type", "error", "data", Objects.requireNonNullElse(message, "File transfer failed.")));
    }

    private void send(WebSocketSession session, Map<String, ?> payload) {
        if (!session.isOpen()) {
            return;
        }
        try {
            String encoded = objectMapper.writeValueAsString(payload);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(encoded));
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static String authenticatedEmail(WebSocketSession session) {
        if (session.getPrincipal() instanceof Authentication authentication && authentication.isAuthenticated()) {
            return authentication.getName();
        }
        return null;
    }

    private static String safeTransferMessage(RuntimeException exception, String fallback) {
        String message = exception.getMessage();
        if (message != null && (message.startsWith("Remote path ") || message.startsWith("Upload exceeds"))) {
            return message;
        }
        return fallback;
    }

    private static String fileExtension(String fileName) {
        String normalized = stringValue(fileName);
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        int dot = normalized.lastIndexOf('.');
        return dot <= 0 || dot == normalized.length() - 1 ? "none" : normalized.substring(dot);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string) {
            try {
                return Long.parseLong(string);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private class TransferConnection {
        private final String email;
        private final String sessionId;
        private volatile UploadState upload;

        private TransferConnection(String email, String sessionId) {
            this.email = email;
            this.sessionId = sessionId;
        }

        private boolean operationActive() {
            return upload != null;
        }

        private void close() {
            clearUpload(this);
        }
    }

    private static class UploadState {
        private final FileTransferProcess process;
        private final Future<String> stderr;
        private final String remotePath;
        private final String fileName;
        private final long expectedBytes;
        private long receivedBytes;

        private UploadState(
                FileTransferProcess process,
                Future<String> stderr,
                String remotePath,
                String fileName,
                long expectedBytes
        ) {
            this.process = process;
            this.stderr = stderr;
            this.remotePath = remotePath;
            this.fileName = fileName;
            this.expectedBytes = expectedBytes;
        }

        private void close() {
            process.close();
        }
    }

}
