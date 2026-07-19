package kim.mingyo.browserssh.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CloudflareAccessTokenResolver implements BearerTokenResolver {
    static final String ASSERTION_HEADER = "Cf-Access-Jwt-Assertion";
    static final String AUTHORIZATION_COOKIE = "CF_Authorization";

    @Override
    public String resolve(HttpServletRequest request) {
        String assertion = request.getHeader(ASSERTION_HEADER);
        if (StringUtils.hasText(assertion)) {
            return assertion.trim();
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (AUTHORIZATION_COOKIE.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                return cookie.getValue().trim();
            }
        }
        return null;
    }
}
