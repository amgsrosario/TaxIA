package com.knowledgeflow.ingestion.atfaq;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Security matrix of /api/v1/admin/ingestion/at-faq/**:
 *   not authenticated → 401
 *   role USER         → 403
 *   role ADMIN        → passes authorization; with the module disabled by
 *                       default the run endpoints answer 400, proving no
 *                       collection can be triggered without explicit opt-in.
 *   run of another organization → 404
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AtFaqIngestionControllerSecurityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private AtFaqIngestionRunRepository runRepository;

    private Organization orgA;
    private Organization orgB;
    private AtFaqIngestionRun runOfOrgB;

    @BeforeEach
    void setUp() {
        orgA = organizationRepository.save(new Organization("Org A AT-FAQ", null));
        orgB = organizationRepository.save(new Organization("Org B AT-FAQ", null));
        runOfOrgB = runRepository.save(
                new AtFaqIngestionRun(orgB, AtFaqRunMode.DRY_RUN, UUID.randomUUID()));
    }

    private RequestPostProcessor jwtFor(Organization org, String role) {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("organization_id", org.getId().toString())
                        .claim("email", role.toLowerCase() + "@atfaq.test")
                        .claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    @DisplayName("Sem autenticação → 401 em todos os endpoints")
    void unauthenticatedIs401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/ingestion/at-faq/discover"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/ingestion/at-faq/dry-run"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/ingestion/at-faq/import"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/ingestion/at-faq/runs/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Role USER → 403")
    void userRoleIs403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/ingestion/at-faq/dry-run").with(jwtFor(orgA, "USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/ingestion/at-faq/import").with(jwtFor(orgA, "USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN com módulo desactivado (default) → 400, nenhuma recolha possível")
    void adminWithModuleDisabledIs400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/ingestion/at-faq/dry-run").with(jwtFor(orgA, "ADMIN")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/admin/ingestion/at-faq/import").with(jwtFor(orgA, "ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("ADMIN não vê runs de outra organização → 404")
    void adminCannotReadRunsOfOtherOrganization() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ingestion/at-faq/runs/" + runOfOrgB.getId())
                        .with(jwtFor(orgA, "ADMIN")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/admin/ingestion/at-faq/runs/" + runOfOrgB.getId())
                        .with(jwtFor(orgB, "ADMIN")))
                .andExpect(status().isOk());
    }
}
