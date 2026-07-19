package kim.mingyo.browserssh.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "browser-ssh.terminal")
public record TerminalProperties(
        @NotBlank String command,
        @NotNull Path home,
        @NotBlank String targetId,
        @NotNull Duration idleTimeout,
        @NotNull Duration maxSessionDuration,
        @Min(1) @Max(4) int maxConcurrentSessions,
        @Min(1024) @Max(65536) int maxInputCharacters,
        @Min(30) @Max(1000) int maxMessagesPerSecond
) {
    private static final Pattern TARGET_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    public TerminalProperties {
        if (targetId == null || !TARGET_ID.matcher(targetId).matches()) {
            throw new IllegalArgumentException("Target ID must contain only letters, numbers, dots, underscores, or hyphens.");
        }
    }

    public Path commandAsPath() {
        return Path.of(command);
    }
}
