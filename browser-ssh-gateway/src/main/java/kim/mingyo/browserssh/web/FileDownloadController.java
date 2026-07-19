package kim.mingyo.browserssh.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kim.mingyo.browserssh.config.AccessProperties;
import kim.mingyo.browserssh.terminal.DownloadInspection;
import kim.mingyo.browserssh.terminal.FileTransferProcess;
import kim.mingyo.browserssh.terminal.LocalTerminalService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/files")
public class FileDownloadController {
    private static final Logger log = LoggerFactory.getLogger(FileDownloadController.class);
    private static final Duration PROCESS_EXIT_TIMEOUT = Duration.ofSeconds(20);
    private static final int STREAM_BUFFER_BYTES = 64 * 1024;
    private static final int ERROR_LIMIT_BYTES = 16 * 1024;

    private final LocalTerminalService terminalService;
    private final DownloadGrantStore grantStore;
    private final AccessProperties accessProperties;
    private final FileTransferCapacity capacity;
    private final ExecutorService stderrReaders = Executors.newVirtualThreadPerTaskExecutor();

    public FileDownloadController(
            LocalTerminalService terminalService,
            DownloadGrantStore grantStore,
            AccessProperties accessProperties,
            FileTransferCapacity capacity
    ) {
        this.terminalService = terminalService;
        this.grantStore = grantStore;
        this.accessProperties = accessProperties;
        this.capacity = capacity;
    }

    @PreDestroy
    public void stop() {
        stderrReaders.shutdownNow();
    }

    @PostMapping(path = "/inspect", consumes = MediaType.APPLICATION_JSON_VALUE)
    public InspectionResponse inspect(
            @Valid @RequestBody InspectionRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        requireSameOrigin(servletRequest);
        if (!capacity.tryStartOperation()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "File transfer limit reached.");
        }
        DownloadInspection inspection;
        try {
            inspection = terminalService.inspectDownload(request.remotePath());
        } finally {
            capacity.finishOperation();
        }
        boolean allowed = inspection.sizeBytes() <= LocalTerminalService.MAX_DOWNLOAD_BYTES;
        if (!allowed) {
            return InspectionResponse.rejected(inspection);
        }
        DownloadGrantStore.IssuedGrant grant = grantStore.issue(authentication.getName(), inspection);
        return InspectionResponse.allowed(inspection, grant);
    }

    @GetMapping("/download/{token}")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable String token,
            Authentication authentication
    ) {
        if (!capacity.tryStartOperation()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "File transfer limit reached.");
        }
        DownloadInspection inspection;
        try {
            inspection = grantStore.consume(token, authentication.getName());
        } catch (RuntimeException ex) {
            capacity.finishOperation();
            throw ex;
        }
        MediaType contentType = inspection.kind() == DownloadInspection.Kind.DIRECTORY
                ? MediaType.parseMediaType("application/zip")
                : MediaType.APPLICATION_OCTET_STREAM;
        StreamingResponseBody stream = output -> {
            try {
                streamDownload(authentication.getName(), inspection, output);
            } finally {
                capacity.finishOperation();
            }
        };
        return ResponseEntity.ok()
                .contentType(contentType)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(inspection.downloadName(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(stream);
    }

    @ExceptionHandler(DownloadGrantStore.ExpiredDownloadGrantException.class)
    public ResponseEntity<Map<String, String>> expiredLink() {
        return ResponseEntity.status(HttpStatus.GONE)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("error", "Download link is expired or already used."));
    }

    private void streamDownload(
            String email,
            DownloadInspection inspection,
            java.io.OutputStream output
    ) throws IOException {
        long sentBytes = 0;
        try (FileTransferProcess process = terminalService.startDownload(inspection)) {
            Future<String> stderr = stderrReaders.submit(() -> readLimited(process.stderr()));
            byte[] buffer = new byte[STREAM_BUFFER_BYTES];
            int read;
            while ((read = process.stdout().read(buffer)) >= 0) {
                if (sentBytes + read > LocalTerminalService.MAX_DOWNLOAD_BYTES) {
                    throw new IOException("Download exceeds the 2 GiB limit.");
                }
                output.write(buffer, 0, read);
                sentBytes += read;
            }
            output.flush();
            int exitCode;
            try {
                exitCode = process.waitFor(PROCESS_EXIT_TIMEOUT);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Download was interrupted.", ex);
            }
            futureText(stderr);
            if (exitCode != 0) {
                log.warn("Browser SSH HTTP download failed: email={}, kind={}, exit={}",
                        email, inspection.kind(), exitCode);
                throw new IOException("Remote download process failed.");
            }
            log.info("Browser SSH HTTP download completed: email={}, kind={}, bytes={}",
                    email, inspection.kind(), sentBytes);
        }
    }

    private void requireSameOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (!accessProperties.publicOrigin().equals(origin)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Origin is not allowed.");
        }
    }

    private static String readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() < ERROR_LIMIT_BYTES) {
                output.write(buffer, 0, Math.min(read, ERROR_LIMIT_BYTES - output.size()));
            }
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String futureText(Future<String> future) {
        try {
            return future.get();
        } catch (Exception ex) {
            return "";
        }
    }

    public record InspectionRequest(@NotBlank @Size(max = 255) String remotePath) {
    }

    public record InspectionResponse(
            String kind,
            String name,
            String downloadName,
            long sizeBytes,
            long fileCount,
            long maxBytes,
            boolean allowed,
            String downloadUrl,
            String expiresAt
    ) {
        static InspectionResponse allowed(
                DownloadInspection inspection,
                DownloadGrantStore.IssuedGrant grant
        ) {
            return new InspectionResponse(
                    inspection.kind().name().toLowerCase(),
                    displayName(inspection),
                    inspection.downloadName(),
                    inspection.sizeBytes(),
                    inspection.fileCount(),
                    LocalTerminalService.MAX_DOWNLOAD_BYTES,
                    true,
                    "/api/files/download/" + grant.token(),
                    grant.expiresAt().toString()
            );
        }

        static InspectionResponse rejected(DownloadInspection inspection) {
            return new InspectionResponse(
                    inspection.kind().name().toLowerCase(),
                    displayName(inspection),
                    inspection.downloadName(),
                    inspection.sizeBytes(),
                    inspection.fileCount(),
                    LocalTerminalService.MAX_DOWNLOAD_BYTES,
                    false,
                    null,
                    null
            );
        }

        private static String displayName(DownloadInspection inspection) {
            String downloadName = inspection.downloadName();
            return inspection.kind() == DownloadInspection.Kind.DIRECTORY
                    ? downloadName.substring(0, downloadName.length() - 4)
                    : downloadName;
        }
    }
}
