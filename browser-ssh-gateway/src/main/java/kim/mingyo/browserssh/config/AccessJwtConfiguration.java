package kim.mingyo.browserssh.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
public class AccessJwtConfiguration {
    @Bean
    JwtDecoder accessJwtDecoder(AccessProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefaultWithIssuer(properties.issuer());
        OAuth2TokenValidator<Jwt> audience = jwt -> containsAudience(jwt, properties.audience())
                ? OAuth2TokenValidatorResult.success()
                : failure("invalid_audience", "Cloudflare Access audience is not allowed.");
        OAuth2TokenValidator<Jwt> email = jwt -> containsEmail(jwt, properties.allowedEmails())
                ? OAuth2TokenValidatorResult.success()
                : failure("invalid_email", "Cloudflare Access email is not allowed.");
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(defaults, audience, email));
        return decoder;
    }

    static boolean containsAudience(Jwt jwt, String requiredAudience) {
        return jwt.getAudience() != null && jwt.getAudience().contains(requiredAudience);
    }

    static boolean containsEmail(Jwt jwt, List<String> allowedEmails) {
        String email = jwt.getClaimAsString("email");
        return email != null && allowedEmails.contains(email.trim().toLowerCase());
    }

    private static OAuth2TokenValidatorResult failure(String code, String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(code, description, null));
    }
}
