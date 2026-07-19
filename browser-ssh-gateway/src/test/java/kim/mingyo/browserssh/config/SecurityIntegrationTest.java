package kim.mingyo.browserssh.config;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "BROWSER_SSH_ACCESS_TEAM_DOMAIN=test.cloudflareaccess.com",
        "BROWSER_SSH_ACCESS_AUDIENCE=test-aud",
        "BROWSER_SSH_PUBLIC_ORIGIN=https://terminal.example.com",
        "BROWSER_SSH_ALLOWED_EMAILS=owner@example.com",
        "BROWSER_SSH_HOME=/tmp",
        "management.server.port=0"
})
@AutoConfigureMockMvc
class SecurityIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void applicationRequiresCloudflareAccessToken() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validCloudflareCookieLoadsPackagedTerminal() throws Exception {
        when(jwtDecoder.decode("valid-access-token")).thenReturn(jwt());

        mockMvc.perform(get("/").cookie(new Cookie("CF_Authorization", "valid-access-token")))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("default-src 'self'"),
                                org.hamcrest.Matchers.containsString("script-src 'self'"),
                                org.hamcrest.Matchers.containsString("style-src 'self' 'unsafe-inline'")
                        )));
    }

    @Test
    void genericBearerHeaderIsNotAnAuthenticationPath() throws Exception {
        mockMvc.perform(get("/").header("Authorization", "Bearer valid-access-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void downloadInspectionRequiresCloudflareAccessToken() throws Exception {
        mockMvc.perform(post("/api/files/inspect")
                        .contentType("application/json")
                        .content("{\"remotePath\":\"/home/operator/report.txt\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void oneTimeDownloadRequiresCloudflareAccessToken() throws Exception {
        mockMvc.perform(get("/api/files/download/not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    private static Jwt jwt() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("valid-access-token")
                .header("alg", "RS256")
                .issuer("https://test.cloudflareaccess.com")
                .audience(List.of("test-aud"))
                .subject("subject")
                .claim("email", "owner@example.com")
                .issuedAt(now.minusSeconds(5))
                .expiresAt(now.plusSeconds(300))
                .build();
    }
}
