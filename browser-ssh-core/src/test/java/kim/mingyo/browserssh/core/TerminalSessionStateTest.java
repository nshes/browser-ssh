package kim.mingyo.browserssh.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TerminalSessionStateTest {
    @Test
    void tracksIdleAndMaximumDurationIndependently() {
        TerminalSessionState state = new TerminalSessionState(
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                2,
                1_000
        );

        state.markActivity(8_000);

        assertFalse(state.isIdle(17_999));
        assertTrue(state.isIdle(18_000));
        assertFalse(state.maximumDurationReached(30_999));
        assertTrue(state.maximumDurationReached(31_000));
    }

    @Test
    void resetsTheMessageRateWindowAfterOneSecond() {
        TerminalSessionState state = new TerminalSessionState(
                Duration.ZERO,
                Duration.ZERO,
                2,
                1_000
        );

        assertTrue(state.allowMessage(1_100));
        assertTrue(state.allowMessage(1_200));
        assertFalse(state.allowMessage(1_300));
        assertTrue(state.allowMessage(2_000));
    }
}
