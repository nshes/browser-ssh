package kim.mingyo.browserssh.web;

import jakarta.servlet.http.HttpServletRequest;
import kim.mingyo.browserssh.config.AccessProperties;
import kim.mingyo.browserssh.config.FileTransferProperties;
import kim.mingyo.browserssh.terminal.DownloadInspection;
import kim.mingyo.browserssh.terminal.FileTransferProcess;
import kim.mingyo.browserssh.terminal.LocalTerminalService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileDownloadControllerTest {
    private static final String ORIGIN = "https://ssh.example.com";
    private final LocalTerminalService terminalService = mock(LocalTerminalService.class);
    private FileDownloadController controller;
    private FileTransferCapacity capacity;

    @BeforeEach
    void setUp() {
        AccessProperties accessProperties = new AccessProperties(
                "team.cloudflareaccess.com",
                "audience",
                ORIGIN,
                List.of("owner@example.com")
        );
        capacity = new FileTransferCapacity(new FileTransferProperties(2, 2, Duration.ofMinutes(1)));
        controller = new FileDownloadController(
                terminalService,
                new DownloadGrantStore(),
                accessProperties,
                capacity
        );
    }

    @AfterEach
    void stopController() {
        controller.stop();
    }

    @Test
    void inspectedFileStreamsThroughOneTimeLink() throws Exception {
        DownloadInspection inspection = new DownloadInspection(
                DownloadInspection.Kind.FILE,
                "/home/operator/report.txt",
                "report.txt",
                5,
                1
        );
        when(terminalService.inspectDownload(inspection.remotePath())).thenReturn(inspection);
        FileTransferProcess process = mock(FileTransferProcess.class);
        when(process.stdout()).thenReturn(new ByteArrayInputStream("hello".getBytes()));
        when(process.stderr()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.waitFor(Duration.ofSeconds(20))).thenReturn(0);
        when(terminalService.startDownload(inspection)).thenReturn(process);

        FileDownloadController.InspectionResponse inspected = controller.inspect(
                new FileDownloadController.InspectionRequest(inspection.remotePath()),
                authentication(),
                sameOriginRequest()
        );
        String token = inspected.downloadUrl().substring(inspected.downloadUrl().lastIndexOf('/') + 1);
        var response = controller.download(token, authentication());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);

        assertThat(output.toString()).isEqualTo("hello");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment").contains("report.txt");
        assertThatThrownBy(() -> controller.download(token, authentication()))
                .isInstanceOf(DownloadGrantStore.ExpiredDownloadGrantException.class);
    }

    @Test
    void oversizedItemIsReportedWithoutIssuingLink() {
        DownloadInspection inspection = new DownloadInspection(
                DownloadInspection.Kind.DIRECTORY,
                "/home/operator/archive",
                "archive.zip",
                LocalTerminalService.MAX_DOWNLOAD_BYTES + 1,
                10
        );
        when(terminalService.inspectDownload(inspection.remotePath())).thenReturn(inspection);

        FileDownloadController.InspectionResponse response = controller.inspect(
                new FileDownloadController.InspectionRequest(inspection.remotePath()),
                authentication(),
                sameOriginRequest()
        );

        assertThat(response.allowed()).isFalse();
        assertThat(response.downloadUrl()).isNull();
    }

    @Test
    void inspectionIsRejectedWhenTransferCapacityIsExhausted() {
        assertThat(capacity.tryStartOperation()).isTrue();
        assertThat(capacity.tryStartOperation()).isTrue();

        assertThatThrownBy(() -> controller.inspect(
                new FileDownloadController.InspectionRequest("/home/operator/report.txt"),
                authentication(),
                sameOriginRequest()
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429 TOO_MANY_REQUESTS");

        capacity.finishOperation();
        capacity.finishOperation();
    }

    private static Authentication authentication() {
        return UsernamePasswordAuthenticationToken.authenticated("owner@example.com", "n/a", List.of());
    }

    private static HttpServletRequest sameOriginRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(HttpHeaders.ORIGIN)).thenReturn(ORIGIN);
        return request;
    }
}
