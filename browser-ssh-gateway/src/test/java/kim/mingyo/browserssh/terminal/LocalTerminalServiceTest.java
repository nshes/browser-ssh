package kim.mingyo.browserssh.terminal;

import kim.mingyo.browserssh.config.TerminalProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class LocalTerminalServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void startsConfiguredCommandInsidePty() throws Exception {
        Path command = tempDir.resolve("broker-client");
        Files.writeString(command, """
                #!/bin/sh
                test "$1" = "--target" && test "$2" = "test-target"
                test "$3" = "--mode" && test "$4" = "terminal"
                exec /bin/bash
                """);
        Files.setPosixFilePermissions(command, PosixFilePermissions.fromString("rwx------"));
        TerminalProperties properties = new TerminalProperties(
                command.toString(),
                Path.of("/tmp"),
                "test-target",
                Duration.ofMinutes(15),
                Duration.ofHours(2),
                1,
                16384,
                300
        );
        LocalTerminalService service = new LocalTerminalService(properties);

        try (TerminalProcess process = service.start();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            process.write("printf '__PTY_READY__\\n'\nexit\n");
            String output = executor.submit(() ->
                            new String(process.stdout().readAllBytes(), StandardCharsets.UTF_8))
                    .get(10, TimeUnit.SECONDS);

            assertThat(output).contains("__PTY_READY__");
        }
    }

    @Test
    void requiresAbsoluteTransferPathsAndLeavesPolicyToBroker() {
        LocalTerminalService service = new LocalTerminalService(properties());

        assertThat(service.validateRemotePath("/home/operator/uploads/file.txt"))
                .isEqualTo("/home/operator/uploads/file.txt");
        assertThat(service.validateRemotePath("/srv/shared/file.txt"))
                .isEqualTo("/srv/shared/file.txt");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                service.validateRemotePath("relative/file.txt")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parsesDirectoryInspectionForZipDownload() {
        DownloadInspection inspection = LocalTerminalService.parseInspection(
                "/home/operator/reports",
                "DIRECTORY\t2147483648\t42"
        );

        assertThat(inspection.kind()).isEqualTo(DownloadInspection.Kind.DIRECTORY);
        assertThat(inspection.downloadName()).isEqualTo("reports.zip");
        assertThat(inspection.sizeBytes()).isEqualTo(2L * 1024L * 1024L * 1024L);
        assertThat(inspection.fileCount()).isEqualTo(42);
    }

    @Test
    void rejectsMalformedInspection() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                LocalTerminalService.parseInspection("/home/operator/file", "FILE\tinvalid\t1")))
                .isInstanceOf(IllegalStateException.class);
    }

    private static TerminalProperties properties() {
        return new TerminalProperties(
                "/bin/bash",
                Path.of("/tmp"),
                "test-target",
                Duration.ofMinutes(15),
                Duration.ofHours(2),
                1,
                16384,
                300
        );
    }
}
