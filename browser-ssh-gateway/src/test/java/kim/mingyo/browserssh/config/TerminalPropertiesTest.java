package kim.mingyo.browserssh.config;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TerminalPropertiesTest {
    @Test
    void rejectsTargetIdsThatCouldBecomeBrokerArguments() {
        assertThatThrownBy(() -> properties("../target"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("target id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("-option"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TerminalProperties properties(String targetId) {
        return new TerminalProperties(
                "/bin/true",
                Path.of("/tmp"),
                targetId,
                Duration.ofMinutes(15),
                Duration.ofHours(2),
                2,
                16384,
                300
        );
    }
}
