package kim.mingyo.browserssh.core;

public record TerminalClientMessage(Type type, String data, int columns, int rows) {
    public enum Type {
        HEARTBEAT,
        INPUT,
        RESIZE,
        UNKNOWN
    }

    public static TerminalClientMessage heartbeat() {
        return new TerminalClientMessage(Type.HEARTBEAT, "", -1, -1);
    }

    public static TerminalClientMessage input(String data) {
        return new TerminalClientMessage(Type.INPUT, data == null ? "" : data, -1, -1);
    }

    public static TerminalClientMessage resize(int columns, int rows) {
        return new TerminalClientMessage(Type.RESIZE, "", columns, rows);
    }

    public static TerminalClientMessage unknown() {
        return new TerminalClientMessage(Type.UNKNOWN, "", -1, -1);
    }
}
