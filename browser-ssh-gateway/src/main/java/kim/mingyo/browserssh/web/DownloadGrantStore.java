package kim.mingyo.browserssh.web;

import kim.mingyo.browserssh.terminal.DownloadInspection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class DownloadGrantStore {
    static final Duration TOKEN_TTL = Duration.ofMinutes(2);
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_PENDING_GRANTS = 32;

    private final Map<String, DownloadGrant> grants = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom;
    private final Clock clock;

    public DownloadGrantStore() {
        this(new SecureRandom(), Clock.systemUTC());
    }

    DownloadGrantStore(SecureRandom secureRandom, Clock clock) {
        this.secureRandom = secureRandom;
        this.clock = clock;
    }

    public IssuedGrant issue(String email, DownloadInspection inspection) {
        purgeExpired();
        if (grants.size() >= MAX_PENDING_GRANTS) {
            throw new IllegalStateException("Too many download links are pending.");
        }
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Instant expiresAt = clock.instant().plus(TOKEN_TTL);
        grants.put(digest(token), new DownloadGrant(normalizeEmail(email), inspection, expiresAt));
        return new IssuedGrant(token, expiresAt);
    }

    public DownloadInspection consume(String token, String email) {
        String key = digest(token);
        DownloadGrant grant = grants.get(key);
        if (grant == null) {
            throw new ExpiredDownloadGrantException();
        }
        if (!grant.expiresAt().isAfter(clock.instant())) {
            grants.remove(key, grant);
            throw new ExpiredDownloadGrantException();
        }
        if (!grant.email().equals(normalizeEmail(email))) {
            throw new ExpiredDownloadGrantException();
        }
        if (!grants.remove(key, grant)) {
            throw new ExpiredDownloadGrantException();
        }
        return grant.inspection();
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        grants.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String digest(String token) {
        if (token == null || token.length() < 32 || token.length() > 128) {
            throw new ExpiredDownloadGrantException();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    public record IssuedGrant(String token, Instant expiresAt) {
    }

    private record DownloadGrant(String email, DownloadInspection inspection, Instant expiresAt) {
    }

    public static final class ExpiredDownloadGrantException extends RuntimeException {
        public ExpiredDownloadGrantException() {
            super("Download link is expired or already used.");
        }
    }
}
