package com.knowledgeflow.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knowledgeflow.organizations.repository.OrganizationUserRepository;
import com.knowledgeflow.users.entity.Role;
import com.knowledgeflow.users.enums.RoleName;
import com.knowledgeflow.users.repository.RoleRepository;
import com.knowledgeflow.users.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * HTTP-level operational hardening:
 * correlationId contract, restrictive CORS, secure bootstrap-admin and payload
 * limits, all through the real security filter chain.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class OperationalHardeningHttpTest {

    private static final String TEST_BOOTSTRAP_SECRET = "test-bootstrap-secret";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationUserRepository organizationUserRepository;
    @Autowired private RoleRepository roleRepository;

    @BeforeEach
    void ensureAdminRoleSeeded() {
        roleRepository.findByName(RoleName.ADMIN)
                .orElseGet(() -> roleRepository.save(
                        new Role(RoleName.ADMIN, "Administra a organizacao e utilizadores")));
    }

    // -------------------------------------------------------------------------
    // Correlation ID
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CorrelationId válido recebido é aceite e devolvido")
    void validCorrelationIdIsEchoed() throws Exception {
        mockMvc.perform(get("/api/v1/health").header("X-Correlation-ID", "pedido-cliente-0001"))
                .andExpect(header().string("X-Correlation-ID", "pedido-cliente-0001"));
    }

    @Test
    @DisplayName("Sem correlationId, o servidor gera um UUID e devolve-o")
    void missingCorrelationIdIsGenerated() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/health")).andReturn();
        String generated = result.getResponse().getHeader("X-Correlation-ID");
        assertThat(generated).isNotBlank();
        assertThat(UUID.fromString(generated)).isNotNull(); // UUID válido
    }

    @Test
    @DisplayName("CorrelationId perigoso ou demasiado longo é substituído por UUID gerado")
    void unsafeCorrelationIdIsReplaced() throws Exception {
        String tooLong = "x".repeat(200);
        MvcResult longResult = mockMvc.perform(get("/api/v1/health")
                        .header("X-Correlation-ID", tooLong)).andReturn();
        assertThat(longResult.getResponse().getHeader("X-Correlation-ID"))
                .isNotEqualTo(tooLong);

        String injection = "abc\r\nSet-Cookie: pwned=1";
        MvcResult injResult = mockMvc.perform(get("/api/v1/health")
                        .header("X-Correlation-ID", injection)).andReturn();
        assertThat(injResult.getResponse().getHeader("X-Correlation-ID"))
                .isNotEqualTo(injection)
                .doesNotContain("Set-Cookie");
    }

    // -------------------------------------------------------------------------
    // CORS
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CORS: origem permitida recebe Access-Control-Allow-Origin")
    void corsAllowsConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/health")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    @Test
    @DisplayName("CORS: origem desconhecida é rejeitada")
    void corsRejectsUnknownOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/health")
                        .header("Origin", "http://atacante.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // bootstrap-admin
    // -------------------------------------------------------------------------

    private String bootstrapBody(String suffix) {
        return """
                {
                  "organizationName": "Org Bootstrap %s",
                  "taxIdentifier": null,
                  "adminEmail": "bootstrap-%s@hardening.test",
                  "adminFullName": "Bootstrap Admin",
                  "adminPassword": "password-123"
                }
                """.formatted(suffix, suffix);
    }

    @Test
    @DisplayName("Bootstrap sem secret → 403; secret errado → 403")
    void bootstrapRejectsMissingOrWrongSecret() throws Exception {
        mockMvc.perform(post("/api/v1/auth/bootstrap-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bootstrapBody("nosecret")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/bootstrap-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Bootstrap-Secret", "segredo-errado")
                        .content(bootstrapBody("wrongsecret")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Bootstrap válido em BD vazia → 201; segunda tentativa → 409")
    void bootstrapIsSingleUse() throws Exception {
        organizationUserRepository.deleteAll();
        userRepository.deleteAll();

        mockMvc.perform(post("/api/v1/auth/bootstrap-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Bootstrap-Secret", TEST_BOOTSTRAP_SECRET)
                        .content(bootstrapBody("first")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        mockMvc.perform(post("/api/v1/auth/bootstrap-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Bootstrap-Secret", TEST_BOOTSTRAP_SECRET)
                        .content(bootstrapBody("second")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Resposta de bootstrap bloqueado não expõe o segredo configurado")
    void bootstrapErrorDoesNotLeakSecret() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/bootstrap-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Bootstrap-Secret", "segredo-errado")
                        .content(bootstrapBody("leakcheck")))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(TEST_BOOTSTRAP_SECRET)
                .doesNotContain("segredo-errado");
    }

    // -------------------------------------------------------------------------
    // Payload limits
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Pergunta acima do limite (4000 chars) → 400 com erro estruturado")
    void oversizedQuestionIsRejected() throws Exception {
        String hugeQuestion = "a".repeat(4001);
        RequestPostProcessor admin = jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("organization_id", UUID.randomUUID().toString())
                        .claim("email", "admin@hardening.test")
                        .claim("roles", List.of("ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

        mockMvc.perform(post("/api/v1/admin/ai/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + hugeQuestion + "\"}")
                        .with(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
