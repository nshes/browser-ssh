package kim.mingyo.browserssh.config;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "browser-ssh.access")
public record AccessProperties(
        @NotBlank String teamDomain,
        @NotBlank String audience,
        @NotBlank String publicOrigin,
        @NotEmpty List<@Email String> allowedEmails
) {
    private static final Pattern TEAM_DOMAIN = Pattern.compile("[a-z0-9-]+\\.cloudflareaccess\\.com");

    public AccessProperties {
        allowedEmails = allowedEmails == null
                ? List.of()
                : allowedEmails.stream().map(String::trim).map(String::toLowerCase).distinct().toList();
        String normalizedTeamDomain = normalizeTeamDomain(teamDomain);
        if (!TEAM_DOMAIN.matcher(normalizedTeamDomain).matches()) {
            throw new IllegalArgumentException("Access team domain must be <team>.cloudflareaccess.com.");
        }
        URI origin = URI.create(publicOrigin);
        if (!"https".equals(origin.getScheme())
                || origin.getHost() == null
                || origin.getPort() != -1
                || (origin.getPath() != null && !origin.getPath().isEmpty())
                || origin.getQuery() != null
                || origin.getFragment() != null) {
            throw new IllegalArgumentException("Public origin must be an HTTPS origin without a path.");
        }
    }

    public String issuer() {
        return "https://" + normalizedTeamDomain();
    }

    public String jwkSetUri() {
        return issuer() + "/cdn-cgi/access/certs";
    }

    public String normalizedTeamDomain() {
        return normalizeTeamDomain(teamDomain);
    }

    private static String normalizeTeamDomain(String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase().replaceFirst("^https://", "").replaceFirst("/+$", "");
    }
}
