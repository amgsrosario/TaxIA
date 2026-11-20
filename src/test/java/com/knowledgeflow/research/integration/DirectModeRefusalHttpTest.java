package com.knowledgeflow.research.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knowledgeflow.security.StaffAuthorities;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import com.knowledgeflow.research.repository.ResearchInvestigationRepository;
import com.knowledgeflow.research.source.OfficialSourceClient;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exclusividade dos modos por HTTP (§3.1). A guarda de arranque é substituída por um duplo apenas para poder
 * observar o percurso HTTP com o modo integrado ligado; na aplicação real, esse arranque é recusado.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {"knowledgeflow.research.enabled=true", "knowledgeflow.research.ask-integration.enabled=true"})
@AutoConfigureMockMvc
class DirectModeRefusalHttpTest {

    @Autowired MockMvc mockMvc;
    @Autowired ResearchInvestigationRepository investigations;
    @MockBean AskIntegrationGuard guard;
    @MockBean OfficialSourceClient sourceClient;

    private static final String PATH = "/api/v1/admin/research/investigations";
    private static final String BODY = "{\"question\":\"Um não residente pode fazer deduções à coleta no IRS?\"}";

    /** Igual ao auxiliar de {@code AdminResearchControllerSecurityTest} (privado do pacote do controlador). */
    private static RequestPostProcessor jwtFor(UUID org, String role) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("organization_id", org.toString())
                        .claim("email", role.toLowerCase() + "@research.test")
                        .claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority(StaffAuthorities.STAFF), new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void adminDirectCreationIsForbiddenWithValidOrInvalidBodyAndHasNoEffect() throws Exception {
        long before = investigations.count();
        UUID org = UUID.randomUUID();
        mockMvc.perform(post(PATH).with(jwtFor(org, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(PATH).with(jwtFor(org, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(PATH).with(jwtFor(org, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isForbidden());
        assertThat(investigations.count()).isEqualTo(before);
        verifyNoInteractions(sourceClient);
    }

    @Test
    void securityStillAppliesFirst() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PATH).with(jwtFor(UUID.randomUUID(), "USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void traceReadingIsNotAffected() throws Exception {
        // O rasto integral continua acessível a ADMIN (auditoria); investigação inexistente → 404, não 403.
        mockMvc.perform(get(PATH + "/" + UUID.randomUUID())
                        .with(jwtFor(UUID.randomUUID(), "ADMIN")))
                .andExpect(status().isNotFound());
    }
}
