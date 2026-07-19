package kim.mingyo.browserssh.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "browser-ssh.file-transfer")
public record FileTransferProperties(
        @Min(1) @Max(8) int maxConcurrentConnections,
        @Min(1) @Max(4) int maxConcurrentOperations,
        @NotNull Duration connectionIdleTimeout
) {
    public FileTransferProperties {
        if (connectionIdleTimeout != null
                && (connectionIdleTimeout.compareTo(Duration.ofSeconds(30)) < 0
                || connectionIdleTimeout.compareTo(Duration.ofMinutes(10)) > 0)) {
            throw new IllegalArgumentException("Transfer connection idle timeout must be between 30 seconds and 10 minutes.");
        }
    }
}
