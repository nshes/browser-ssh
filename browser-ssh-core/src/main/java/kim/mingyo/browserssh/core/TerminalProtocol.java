package kim.mingyo.browserssh.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;

public final class TerminalProtocol {
    public static final int MIN_COLUMNS = 20;
    public static final int MAX_COLUMNS = 300;
    public static final int MIN_ROWS = 8;
    public static final int MAX_ROWS = 120;

    private TerminalProtocol() {
    }

    public static TerminalClientMessage parse(ObjectMapper objectMapper, String payload) throws IOException {
        JsonNode message = objectMapper.readTree(payload);
        if (message == null || !message.isObject()) {
            return TerminalClientMessage.unknown();
        }
        return switch (message.path("type").asText("")) {
            case "heartbeat" -> TerminalClientMessage.heartbeat();
            case "input" -> TerminalClientMessage.input(message.path("data").asText(""));
            case "resize" -> TerminalClientMessage.resize(
                    dimension(message.path("cols"), MIN_COLUMNS, MAX_COLUMNS),
                    dimension(message.path("rows"), MIN_ROWS, MAX_ROWS)
            );
            default -> TerminalClientMessage.unknown();
        };
    }

    public static Map<String, String> serverMessage(String type, String data) {
        return Map.of("type", type, "data", data);
    }

    private static int dimension(JsonNode value, int minimum, int maximum) {
        if (!value.canConvertToInt()) {
            return -1;
        }
        return Math.max(minimum, Math.min(value.asInt(), maximum));
    }
}
