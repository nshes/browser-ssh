package kim.mingyo.browserssh.config;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileTransferPropertiesTest {
    @Test
    void rejectsIdleTimeoutOutsideSupportedRange() {
        assertThatThrownBy(() -> new FileTransferProperties(2, 2, Duration.ofSeconds(29)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FileTransferProperties(2, 2, Duration.ofMinutes(11)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
