package com.knowledgeflow.common.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import com.knowledgeflow.config.SecurityConfig;
import com.knowledgeflow.security.JwtSecretStartupGuard;
import com.knowledgeflow.security.StaffSessionVerifier;

@WebMvcTest(controllers = HealthController.class,
        properties = "knowledgeflow.security.jwt.secret=test-only-jwt-signing-key-not-a-secret-0123456789")
@Import(SecurityConfig.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // Web slice without JPA: the per-request session verifier and the startup secret guard
    // (ADR-006) are covered by their own tests.
    @MockBean
    private StaffSessionVerifier staffSessionVerifier;

    @MockBean
    private JwtSecretStartupGuard jwtSecretStartupGuard;

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
