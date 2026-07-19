package kim.mingyo.browserssh.terminal;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import kim.mingyo.browserssh.config.TerminalProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class LocalTerminalService {
    public static final long MAX_UPLOAD_BYTES = 100L * 1024L * 1024L;
    public static final long MAX_DOWNLOAD_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final Duration INSPECTION_TIMEOUT = Duration.ofSeconds(30);
    private static final int INSPECTION_OUTPUT_LIMIT = 4096;

    private final TerminalProperties properties;

    public LocalTerminalService(TerminalProperties properties) {
        this.properties = properties;
    }

    public TerminalProcess start() {
        if (!Files.isExecutable(properties.commandAsPath())) {
            throw new IllegalStateException("Configured terminal command is not executable.");
        }
        if (!Files.isDirectory(properties.home())) {
            throw new IllegalStateException("Terminal home directory is unavailable.");
        }

        Map<String, String> environment = new HashMap<>();
        environment.put("HOME", properties.home().toString());
        environment.put("LANG", "C.UTF-8");
        environment.put("LC_ALL", "C.UTF-8");
        String runtimeUser = System.getProperty("user.name", "browser-ssh");
        environment.put("LOGNAME", runtimeUser);
        environment.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        environment.put("SHELL", "/bin/bash");
        environment.put("TERM", "xterm-256color");
        environment.put("USER", runtimeUser);
        copyEnvironment(environment,
                "BROWSER_SSH_BROKER_HOST",
                "BROWSER_SSH_BROKER_PORT",
                "BROWSER_SSH_BROKER_USER",
                "BROWSER_SSH_BROKER_KEY",
                "BROWSER_SSH_BROKER_KNOWN_HOSTS");

        try {
            PtyProcess process = new PtyProcessBuilder()
                    .setCommand(new String[]{
                            properties.command(),
                            "--target", properties.targetId(),
                            "--mode", "terminal"
                    })
                    .setDirectory(properties.home().toString())
                    .setEnvironment(environment)
                    .setInitialColumns(100)
                    .setInitialRows(24)
                    .setRedirectErrorStream(true)
                    .start();
            return new TerminalProcess(process);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not start local terminal.", ex);
        }
    }

    private static void copyEnvironment(Map<String, String> target, String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (StringUtils.hasText(value)) {
                target.put(name, value);
            }
        }
    }

    public FileTransferProcess startUpload(String remotePath, long sizeBytes) {
        if (sizeBytes < 0 || sizeBytes > MAX_UPLOAD_BYTES) {
            throw new IllegalStateException("Upload exceeds the 100 MB limit.");
        }
        return startFileTransfer("upload", remotePath, sizeBytes);
    }

    public DownloadInspection inspectDownload(String remotePath) {
        String normalizedPath = validateRemotePath(remotePath);
        try (FileTransferProcess process = startFileTransfer("inspect", normalizedPath, MAX_DOWNLOAD_BYTES);
             var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            var stdout = readers.submit(() -> process.stdout().readNBytes(INSPECTION_OUTPUT_LIMIT));
            var stderr = readers.submit(() -> process.stderr().readNBytes(INSPECTION_OUTPUT_LIMIT));
            int exitCode = process.waitFor(INSPECTION_TIMEOUT);
            String output = new String(stdout.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).trim();
            String detail = new String(stderr.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).trim();
            if (exitCode != 0) {
                throw new IllegalStateException(detail.isBlank()
                        ? "Remote item is unavailable or unreadable."
                        : detail);
            }
            return parseInspection(normalizedPath, output);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Download inspection was interrupted.", ex);
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException illegalState) {
                throw illegalState;
            }
            throw new IllegalStateException("Could not inspect remote item.", ex);
        }
    }

    public FileTransferProcess startDownload(DownloadInspection inspection) {
        if (inspection == null) {
            throw new IllegalStateException("Download inspection is required.");
        }
        String mode = inspection.kind() == DownloadInspection.Kind.DIRECTORY
                ? "download-archive"
                : "download-file";
        return startFileTransfer(mode, inspection.remotePath(), MAX_DOWNLOAD_BYTES);
    }

    private FileTransferProcess startFileTransfer(String mode, String remotePath, long maxBytes) {
        String normalizedPath = validateRemotePath(remotePath);
        try {
            Process process = new ProcessBuilder(
                    properties.command(),
                    "--target", properties.targetId(),
                    "--mode", mode,
                    "--remote-path", normalizedPath,
                    "--max-bytes", String.valueOf(maxBytes)
            ).directory(properties.home().toFile()).start();
            return new FileTransferProcess(process);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not start file transfer.", ex);
        }
    }

    String validateRemotePath(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Remote path is required.");
        }
        String trimmed = value.trim();
        if (trimmed.indexOf('\0') >= 0 || trimmed.indexOf('\r') >= 0 || trimmed.indexOf('\n') >= 0) {
            throw new IllegalStateException("Remote path contains invalid characters.");
        }
        Path normalized = Path.of(trimmed).normalize();
        if (!normalized.isAbsolute()) {
            throw new IllegalStateException("Remote path must be absolute.");
        }
        if (normalized.toString().length() > 255) {
            throw new IllegalStateException("Remote path is too long.");
        }
        return normalized.toString();
    }

    static DownloadInspection parseInspection(String normalizedPath, String output) {
        String[] fields = output.split("\\t", -1);
        if (fields.length != 3) {
            throw new IllegalStateException("Remote item inspection returned invalid data.");
        }
        DownloadInspection.Kind kind;
        try {
            kind = DownloadInspection.Kind.valueOf(fields[0]);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Remote item inspection returned an invalid type.", ex);
        }
        long sizeBytes;
        long fileCount;
        try {
            sizeBytes = Long.parseLong(fields[1]);
            fileCount = Long.parseLong(fields[2]);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Remote item inspection returned an invalid size.", ex);
        }
        if (sizeBytes < 0 || fileCount < 0 || (kind == DownloadInspection.Kind.FILE && fileCount != 1)) {
            throw new IllegalStateException("Remote item inspection returned invalid data.");
        }
        String baseName = Path.of(normalizedPath).getFileName().toString()
                .replaceAll("[\\\\/\\r\\n\\u0000]", "-");
        if (baseName.isBlank()) {
            baseName = "download";
        }
        String downloadName = kind == DownloadInspection.Kind.DIRECTORY
                ? baseName + ".zip"
                : baseName;
        return new DownloadInspection(kind, normalizedPath, downloadName, sizeBytes, fileCount);
    }
}
