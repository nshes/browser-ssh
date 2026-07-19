package kim.mingyo.browserssh.web;

import kim.mingyo.browserssh.terminal.DownloadInspection;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownloadGrantStoreTest {
    private static final DownloadInspection INSPECTION = new DownloadInspection(
            DownloadInspection.Kind.FILE,
            "/home/operator/report.txt",
            "report.txt",
            12,
            1
    );

    @Test
    void grantCanBeConsumedOnlyOnce() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-13T00:00:00Z"));
        DownloadGrantStore store = new DownloadGrantStore(new SecureRandom(), clock);
        String token = store.issue("Owner@Example.com", INSPECTION).token();

        assertThat(store.consume(token, "owner@example.com")).isEqualTo(INSPECTION);
        assertThatThrownBy(() -> store.consume(token, "owner@example.com"))
                .isInstanceOf(DownloadGrantStore.ExpiredDownloadGrantException.class);
    }

    @Test
    void wrongIdentityDoesNotConsumeGrant() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-13T00:00:00Z"));
        DownloadGrantStore store = new DownloadGrantStore(new SecureRandom(), clock);
        String token = store.issue("owner@example.com", INSPECTION).token();

        assertThatThrownBy(() -> store.consume(token, "other@example.com"))
                .isInstanceOf(DownloadGrantStore.ExpiredDownloadGrantException.class);
        assertThat(store.consume(token, "owner@example.com")).isEqualTo(INSPECTION);
    }

    @Test
    void grantExpiresAfterTwoMinutes() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-13T00:00:00Z"));
        DownloadGrantStore store = new DownloadGrantStore(new SecureRandom(), clock);
        String token = store.issue("owner@example.com", INSPECTION).token();
        clock.instant = clock.instant.plus(DownloadGrantStore.TOKEN_TTL);

        assertThatThrownBy(() -> store.consume(token, "owner@example.com"))
                .isInstanceOf(DownloadGrantStore.ExpiredDownloadGrantException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
