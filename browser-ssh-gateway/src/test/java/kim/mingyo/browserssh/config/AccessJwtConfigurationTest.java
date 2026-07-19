package kim.mingyo.browserssh.config;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class AccessJwtConfigurationTest {
    @Test
    void requiresExpectedAudienceAndAllowedEmail() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .audience(List.of("access-audience"))
                .claim("email", "Owner@Example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        assertThat(AccessJwtConfiguration.containsAudience(jwt, "access-audience")).isTrue();
        assertThat(AccessJwtConfiguration.containsAudience(jwt, "other-audience")).isFalse();
        assertThat(AccessJwtConfiguration.containsEmail(jwt, List.of("owner@example.com"))).isTrue();
        assertThat(AccessJwtConfiguration.containsEmail(jwt, List.of("other@example.com"))).isFalse();
    }

    @Test
    void accessConfigurationRequiresCloudflareTeamAndExactHttpsOrigin() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new AccessProperties(
                "attacker.example.com",
                "audience",
                "https://terminal.example.com",
                List.of("owner@example.com")
        ))).isInstanceOf(IllegalArgumentException.class);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new AccessProperties(
                "test.cloudflareaccess.com",
                "audience",
                "https://terminal.example.com/path",
                List.of("owner@example.com")
        ))).isInstanceOf(IllegalArgumentException.class);
    }
}
