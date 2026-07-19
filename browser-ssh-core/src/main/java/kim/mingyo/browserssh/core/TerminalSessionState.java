package kim.mingyo.browserssh.core;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class TerminalSessionState {
    private final long openedAtMillis;
    private final long idleTimeoutMillis;
    private final long maximumDurationMillis;
    private final int maxMessagesPerSecond;
    private final AtomicLong lastActivityMillis;
    private long messageWindowStartedMillis;
    private int messagesInWindow;

    public TerminalSessionState(
            Duration idleTimeout,
            Duration maximumDuration,
            int maxMessagesPerSecond,
            long openedAtMillis
    ) {
        this.idleTimeoutMillis = nonNegativeMillis(idleTimeout, "idleTimeout");
        this.maximumDurationMillis = nonNegativeMillis(maximumDuration, "maximumDuration");
        if (maxMessagesPerSecond < 1) {
            throw new IllegalArgumentException("maxMessagesPerSecond must be positive");
        }
        this.maxMessagesPerSecond = maxMessagesPerSecond;
        this.openedAtMillis = openedAtMillis;
        this.lastActivityMillis = new AtomicLong(openedAtMillis);
        this.messageWindowStartedMillis = openedAtMillis;
    }

    public static TerminalSessionState open(
            Duration idleTimeout,
            Duration maximumDuration,
            int maxMessagesPerSecond
    ) {
        return new TerminalSessionState(
                idleTimeout,
                maximumDuration,
                maxMessagesPerSecond,
                System.currentTimeMillis()
        );
    }

    public void markActivity() {
        lastActivityMillis.set(System.currentTimeMillis());
    }

    public void markActivity(long nowMillis) {
        lastActivityMillis.set(nowMillis);
    }

    public synchronized boolean allowMessage(long nowMillis) {
        if (nowMillis - messageWindowStartedMillis >= 1000) {
            messageWindowStartedMillis = nowMillis;
            messagesInWindow = 0;
        }
        messagesInWindow++;
        return messagesInWindow <= maxMessagesPerSecond;
    }

    public boolean isIdle(long nowMillis) {
        return idleTimeoutMillis > 0
                && nowMillis - lastActivityMillis.get() >= idleTimeoutMillis;
    }

    public boolean maximumDurationReached(long nowMillis) {
        return maximumDurationMillis > 0
                && nowMillis - openedAtMillis >= maximumDurationMillis;
    }

    public long watchPeriodMillis(Duration maximumWatchInterval) {
        long maximumWatchMillis = Math.max(1, Objects.requireNonNull(maximumWatchInterval).toMillis());
        long shortest = shortestEnabledTimeout();
        if (shortest <= 0) {
            return maximumWatchMillis;
        }
        return Math.min(maximumWatchMillis, Math.max(250, shortest / 4));
    }

    private long shortestEnabledTimeout() {
        if (idleTimeoutMillis <= 0) {
            return maximumDurationMillis;
        }
        if (maximumDurationMillis <= 0) {
            return idleTimeoutMillis;
        }
        return Math.min(idleTimeoutMillis, maximumDurationMillis);
    }

    private static long nonNegativeMillis(Duration duration, String name) {
        long millis = Objects.requireNonNull(duration, name).toMillis();
        if (millis < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return millis;
    }
}
