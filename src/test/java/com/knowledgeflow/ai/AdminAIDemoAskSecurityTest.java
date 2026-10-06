package com.knowledgeflow.ai;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import com.knowledgeflow.security.StaffAuthorities;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * HTTP-level security of POST /api/v1/admin/ai/demo/ask with the real Spring Security filter chain
 * (JWT resource server + @PreAuthorize). The ADMIN case sends a blank question: a 400 proves the
 * request passed authorization and reached validation, without calling RAG or the AI provider.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AdminAIDemoAskSecurityTest {

    private static final String DEMO_ASK = "/api/v1/admin/ai/demo/ask";

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("Não autenticado → 401")
    void unauthenticated_401() throws Exception {
        mockMvc.perform(post(DEMO_ASK).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("USER → 403")
    void user_403() throws Exception {
        mockMvc.perform(post(DEMO_ASK).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"x\"}")
                        .with(jwtFor("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN passa a autorização (pergunta em branco → 400 de validação)")
    void admin_isAuthorized() throws Exception {
        mockMvc.perform(post(DEMO_ASK).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\" \"}")
                        .with(jwtFor("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private RequestPostProcessor jwtFor(String role) {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("organization_id", UUID.randomUUID().toString())
                        .claim("email", role.toLowerCase() + "@demo-ask.test")
                        .claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority(StaffAuthorities.STAFF), new SimpleGrantedAuthority("ROLE_" + role));
    }
}
