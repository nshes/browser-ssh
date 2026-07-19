package kim.mingyo.browserssh.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import kim.mingyo.browserssh.config.FileTransferProperties;
import kim.mingyo.browserssh.terminal.FileTransferProcess;
import kim.mingyo.browserssh.terminal.LocalTerminalService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileTransferWebSocketHandlerTest {
    private final LocalTerminalService terminalService = mock(LocalTerminalService.class);
    private FileTransferWebSocketHandler handler;

    @AfterEach
    void stopHandler() {
        if (handler != null) {
            handler.stop();
        }
    }

    @Test
    void uploadStreamsBinaryChunksToSshProcess() throws Exception {
        handler = handlerWithConnectionLimit(2);
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        FileTransferProcess process = uploadProcess(stdin);
        when(terminalService.startUpload("/home/operator/uploads/hello.txt", 5)).thenReturn(process);
        WebSocketSession session = session("session-1", true);

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"upload-start","fileName":"hello.txt","sizeBytes":5,
                 "remotePath":"/home/operator/uploads/hello.txt"}
                """));
        handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap("hello".getBytes())));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"upload-complete\"}"));

        assertThat(stdin.toString()).isEqualTo("hello");
        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(messages.capture());
        assertThat(messages.getAllValues())
                .extracting(TextMessage::getPayload)
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"ready\""))
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"upload-ready\""))
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"upload-complete\""));
    }

    @Test
    void concurrentUploadIsRejected() throws Exception {
        handler = handlerWithConnectionLimit(2);
        FileTransferProcess process = uploadProcess(new ByteArrayOutputStream());
        when(terminalService.startUpload("/home/operator/first.txt", 5)).thenReturn(process);
        WebSocketSession first = session("session-1", true);
        WebSocketSession second = session("session-2", true);

        handler.afterConnectionEstablished(first);
        handler.handleTextMessage(first, new TextMessage("""
                {"type":"upload-start","fileName":"first.txt","sizeBytes":5,
                 "remotePath":"/home/operator/first.txt"}
                """));
        handler.afterConnectionEstablished(second);
        handler.handleTextMessage(second, new TextMessage("""
                {"type":"upload-start","fileName":"second.txt","sizeBytes":6,
                 "remotePath":"/home/operator/second.txt"}
                """));

        verify(terminalService, never()).startUpload("/home/operator/second.txt", 6);
        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(second, atLeastOnce()).sendMessage(messages.capture());
        assertThat(messages.getAllValues())
                .extracting(TextMessage::getPayload)
                .anySatisfy(payload -> assertThat(payload).contains("Another upload is already in progress"));
    }

    @Test
    void unauthenticatedTransferSocketIsRejected() throws Exception {
        handler = handlerWithConnectionLimit(2);
        WebSocketSession session = session("session-1", false);

        handler.afterConnectionEstablished(session);

        verify(session).close(any(CloseStatus.class));
        verify(terminalService, never()).startUpload(any(), anyLong());
    }

    @Test
    void excessTransferSocketIsRejected() throws Exception {
        handler = handlerWithConnectionLimit(1);
        WebSocketSession first = session("session-1", true);
        WebSocketSession second = session("session-2", true);

        handler.afterConnectionEstablished(first);
        handler.afterConnectionEstablished(second);

        verify(second).close(any(CloseStatus.class));
        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(second, atLeastOnce()).sendMessage(messages.capture());
        assertThat(messages.getAllValues())
                .extracting(TextMessage::getPayload)
                .anySatisfy(payload -> assertThat(payload).contains("connection limit reached"));
    }

    private FileTransferWebSocketHandler handlerWithConnectionLimit(int connectionLimit) {
        FileTransferProperties properties = new FileTransferProperties(
                connectionLimit,
                2,
                Duration.ofMinutes(1)
        );
        return new FileTransferWebSocketHandler(
                terminalService,
                new ObjectMapper(),
                new FileTransferCapacity(properties),
                properties
        );
    }

    private static FileTransferProcess uploadProcess(ByteArrayOutputStream stdin) throws Exception {
        FileTransferProcess process = mock(FileTransferProcess.class);
        when(process.stdin()).thenReturn(stdin);
        when(process.stderr()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.waitFor(Duration.ofSeconds(20))).thenReturn(0);
        return process;
    }

    private static WebSocketSession session(String id, boolean authenticated) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        AtomicBoolean open = new AtomicBoolean(true);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenAnswer(invocation -> open.get());
        if (authenticated) {
            when(session.getPrincipal()).thenReturn(
                    UsernamePasswordAuthenticationToken.authenticated("owner@example.com", "n/a", List.of())
            );
        }
        org.mockito.Mockito.doAnswer(invocation -> {
            open.set(false);
            return null;
        }).when(session).close(any(CloseStatus.class));
        return session;
    }
}
