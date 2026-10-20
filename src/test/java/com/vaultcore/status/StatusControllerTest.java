package com.vaultcore.status;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vaultcore.security.SecurityConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = StatusController.class, properties = "debug=false")
@Import({SecurityConfiguration.class, StatusControllerTest.TestTokens.class})
class StatusControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void rejectsRequestsWithoutBearerToken() throws Exception {
        mvc.perform(get("/api/v1/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsInvalidBearerToken() throws Exception {
        mvc.perform(get("/api/v1/status").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsStatusForAuthenticatedRequests() throws Exception {
        mvc.perform(get("/api/v1/status").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("vaultcore"))
                .andExpect(jsonPath("$.stage").value("practice-api"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestTokens {

        // Tests filter-chain behaviour; signature/issuer verification remains the real decoder's job.
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                if (!"test-token".equals(token)) {
                    throw new BadJwtException("Invalid test token");
                }
                return Jwt.withTokenValue(token)
                        .header("alg", "RS256")
                        .subject("practice-user")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .build();
            };
        }
    }
}
