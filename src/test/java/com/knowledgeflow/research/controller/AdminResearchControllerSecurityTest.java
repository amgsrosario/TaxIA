package com.knowledgeflow.research.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.research.service.CorpusCoverageChecker;
import com.knowledgeflow.research.service.CorpusCoverageChecker.Coverage;
import com.knowledgeflow.research.source.OfficialSourceClient;
import com.knowledgeflow.security.StaffAuthorities;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Matriz de segurança de /api/v1/admin/research/**:
 *   módulo desligado (omissão) → o endpoint não existe, nenhuma consulta possível;
 *   sem autenticação → 401; role USER → 403; ADMIN → investigação da própria organização;
 *   investigação de outra organização → 404.
 */
class AdminResearchControllerSecurityTest {

    static RequestPostProcessor jwtFor(UUID org, String role) {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("organization_id", org.toString())
                        .claim("email", role.toLowerCase() + "@research.test")
                        .claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority(StaffAuthorities.STAFF), new SimpleGrantedAuthority("ROLE_" + role));
    }

    static final String BODY = "{\"question\":\"Um não residente pode fazer deduções à coleta no IRS?\"}";

    @Nested
    @ActiveProfiles("test")
    @SpringBootTest
    @AutoConfigureMockMvc
    class Disabled {

        @Autowired MockMvc mockMvc;
        @MockBean OfficialSourceClient sourceClient;

        @Test
        @DisplayName("Módulo desligado por omissão → endpoint inexistente para ADMIN, sem consulta")
        void disabledByDefault() throws Exception {
            mockMvc.perform(post("/api/v1/admin/research/investigations").with(jwtFor(UUID.randomUUID(), "ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isNotFound());
            verifyNoInteractions(sourceClient);
        }
    }

    @Nested
    @ActiveProfiles("test")
    @SpringBootTest(properties = "knowledgeflow.research.enabled=true")
    @AutoConfigureMockMvc
    class Enabled {

        @Autowired MockMvc mockMvc;
        @Autowired OrganizationRepository organizationRepository;
        @MockBean OfficialSourceClient sourceClient;
        @MockBean CorpusCoverageChecker corpusChecker;

        Organization orgA;
        Organization orgB;

        @BeforeEach
        void setUp() {
            orgA = organizationRepository.save(new Organization("Org A research", null));
            orgB = organizationRepository.save(new Organization("Org B research", null));
            when(corpusChecker.check(any(), anyString()))
                    .thenReturn(new CorpusCoverageChecker.Result(Coverage.RELEVANT_QA_FOUND, List.of("Q&A"), "coberta"));
        }

        @Test
        @DisplayName("Sem autenticação → 401")
        void unauthenticated() throws Exception {
            mockMvc.perform(post("/api/v1/admin/research/investigations")
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/admin/research/investigations/" + UUID.randomUUID()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Role USER → 403")
        void userForbidden() throws Exception {
            mockMvc.perform(post("/api/v1/admin/research/investigations").with(jwtFor(orgA.getId(), "USER"))
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(sourceClient);
        }

        @Test
        @DisplayName("ADMIN → investigação da própria organização; outra organização não a vê (404)")
        void adminOwnOrganizationOnly() throws Exception {
            String json = mockMvc.perform(post("/api/v1/admin/research/investigations").with(jwtFor(orgA.getId(), "ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("NOT_STARTED"))
                    .andExpect(jsonPath("$.trigger").value("RELEVANT_QA_EXISTS"))
                    .andReturn().getResponse().getContentAsString();
            String id = com.jayway.jsonpath.JsonPath.read(json, "$.id");

            mockMvc.perform(get("/api/v1/admin/research/investigations/" + id).with(jwtFor(orgA.getId(), "ADMIN")))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/admin/research/investigations/" + id).with(jwtFor(orgB.getId(), "ADMIN")))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Pergunta vazia → 400")
        void blankQuestion() throws Exception {
            mockMvc.perform(post("/api/v1/admin/research/investigations").with(jwtFor(orgA.getId(), "ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\" \"}"))
                    .andExpect(status().isBadRequest());
        }
    }
}
