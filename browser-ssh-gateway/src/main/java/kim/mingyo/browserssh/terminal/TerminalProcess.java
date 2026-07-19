package kim.mingyo.browserssh.terminal;

import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class TerminalProcess implements AutoCloseable {
    private final PtyProcess process;
    private final OutputStream stdin;
    private final InputStream stdout;

    TerminalProcess(PtyProcess process) {
        this.process = process;
        this.stdin = process.getOutputStream();
        this.stdout = process.getInputStream();
    }

    public InputStream stdout() {
        return stdout;
    }

    public void write(String input) throws IOException {
        stdin.write(input.getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    public void resize(int columns, int rows) throws IOException {
        process.setWinSize(new WinSize(columns, rows));
    }

    @Override
    public void close() {
        try {
            stdin.close();
        } catch (IOException ignored) {
        }
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
