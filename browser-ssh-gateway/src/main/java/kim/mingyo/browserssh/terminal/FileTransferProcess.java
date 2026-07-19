package kim.mingyo.browserssh.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class FileTransferProcess implements AutoCloseable {
    private final Process process;
    private final OutputStream stdin;
    private final InputStream stdout;
    private final InputStream stderr;

    FileTransferProcess(Process process) {
        this.process = process;
        this.stdin = process.getOutputStream();
        this.stdout = process.getInputStream();
        this.stderr = process.getErrorStream();
    }

    public OutputStream stdin() {
        return stdin;
    }

    public InputStream stdout() {
        return stdout;
    }

    public InputStream stderr() {
        return stderr;
    }

    public int waitFor(Duration timeout) throws InterruptedException {
        Duration effective = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(20)
                : timeout;
        if (!process.waitFor(effective.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            return 124;
        }
        return process.exitValue();
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
