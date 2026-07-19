package kim.mingyo.browserssh.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "browser-ssh.display")
public record DisplayProperties(
        @NotBlank String targetName,
        @NotBlank String uploadPath,
        @NotBlank String downloadPath
) {
    public DisplayProperties {
        requireAbsolutePath(uploadPath, "Upload path");
        requireAbsolutePath(downloadPath, "Download path");
    }

    private static void requireAbsolutePath(String path, String label) {
        if (path == null || !path.startsWith("/") || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(label + " must be an absolute single-line path.");
        }
    }
}
