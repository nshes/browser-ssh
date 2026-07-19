package kim.mingyo.browserssh.config;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class CloudflareAccessTokenResolverTest {
    private final CloudflareAccessTokenResolver resolver = new CloudflareAccessTokenResolver();

    @Test
    void prefersCloudflareAssertionHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CloudflareAccessTokenResolver.ASSERTION_HEADER, "header-token");
        request.setCookies(new Cookie(CloudflareAccessTokenResolver.AUTHORIZATION_COOKIE, "cookie-token"));

        assertThat(resolver.resolve(request)).isEqualTo("header-token");
    }

    @Test
    void fallsBackToCloudflareAuthorizationCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(CloudflareAccessTokenResolver.AUTHORIZATION_COOKIE, "cookie-token"));

        assertThat(resolver.resolve(request)).isEqualTo("cookie-token");
    }

    @Test
    void doesNotAcceptGenericAuthorizationHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer unrelated-token");

        assertThat(resolver.resolve(request)).isNull();
    }
}
