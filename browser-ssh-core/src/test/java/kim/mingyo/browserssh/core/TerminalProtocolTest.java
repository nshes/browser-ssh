package kim.mingyo.browserssh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TerminalProtocolTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesAndClampsResizeMessages() throws Exception {
        TerminalClientMessage message = TerminalProtocol.parse(
                objectMapper,
                "{\"type\":\"resize\",\"cols\":500,\"rows\":2}"
        );

        assertEquals(TerminalClientMessage.Type.RESIZE, message.type());
        assertEquals(TerminalProtocol.MAX_COLUMNS, message.columns());
        assertEquals(TerminalProtocol.MIN_ROWS, message.rows());
    }

    @Test
    void preservesTerminalInput() throws Exception {
        TerminalClientMessage message = TerminalProtocol.parse(
                objectMapper,
                "{\"type\":\"input\",\"data\":\"echo hello\\n\"}"
        );

        assertEquals(TerminalClientMessage.input("echo hello\n"), message);
    }

    @Test
    void treatsEmptyPayloadAsUnknown() throws Exception {
        TerminalClientMessage message = TerminalProtocol.parse(objectMapper, "");

        assertEquals(TerminalClientMessage.unknown(), message);
    }
}
