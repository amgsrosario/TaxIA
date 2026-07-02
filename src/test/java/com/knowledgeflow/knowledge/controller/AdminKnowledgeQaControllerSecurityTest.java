package com.knowledgeflow.knowledge.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * HTTP-level security tests for /api/v1/admin/knowledge/qa/** running the real
 * Spring Security filter chain (JWT resource server + @PreAuthorize).
 *
 * Matrix proved here:
 *   not authenticated  → 401
 *   role USER          → 403
 *   role ADMIN         → 2xx
 *   ADMIN of another organization → 404 (indistinguishable from a missing id)
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminKnowledgeQaControllerSecurityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private KnowledgeQuestionAnswerRepository qaRepository;

    private Organization orgA;
    private Organization orgB;
    private KnowledgeQuestionAnswer qaOfOrgB;

    @BeforeEach
    void setUp() {
        orgA = organizationRepository.save(new Organization("Org A Security", null));
        orgB = organizationRepository.save(new Organization("Org B Security", null));
        qaOfOrgB = qaRepository.save(new KnowledgeQuestionAnswer(
                orgB, "Pergunta ficticia da Org B?", "Resposta ficticia da Org B.", "sec-test", "SEC-B-001"));
    }

    // -------------------------------------------------------------------------
    // JWT helpers — claims identical to the ones issued by JwtService
    // -------------------------------------------------------------------------

    private RequestPostProcessor adminOf(Organization org) {
        return jwtFor(org, "ADMIN");
    }

    private RequestPostProcessor userOf(Organization org) {
        return jwtFor(org, "USER");
    }

    private RequestPostProcessor jwtFor(Organization org, String role) {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("organization_id", org.getId().toString())
                        .claim("email", role.toLowerCase() + "@sec.test")
                        .claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    // -------------------------------------------------------------------------
    // 401 — not authenticated
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Nao autenticado: GET lista → 401")
    void unauthenticated_list_401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/knowledge/qa"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Nao autenticado: GET detalhe → 401")
    void unauthenticated_detail_401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/knowledge/qa/{id}", qaOfOrgB.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Nao autenticado: POST validate/publish/archive → 401")
    void unauthenticated_writes_401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/knowledge/qa/{id}/validate", qaOfOrgB.getId())
                        .param("reviewerName", "x"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/knowledge/qa/{id}/publish", qaOfOrgB.getId())
                        .param("publisherName", "x"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/knowledge/qa/{id}/archive", qaOfOrgB.getId()))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // 403 — authenticated without ADMIN
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("USER: GET lista → 403")
    void user_list_403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/knowledge/qa").with(userOf(orgA)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("USER: GET detalhe e semelhantes → 403")
    void user_reads_403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/knowledge/qa/{id}", qaOfOrgB.getId()).with(userOf(orgB)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/knowledge/qa/similar")
                        .param("question", "Qual a taxa ficticia?").with(userOf(orgB)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("USER: operacoes de escrita (curadoria, fonte, validate, publish, archive, benchmark) → 403")
    void user_writes_403() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/knowledge/qa/{id}/curation", qaOfOrgB.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{}").with(userOf(orgB)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/knowledge/qa/{id}/sources", qaOfOrgB.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"OTHER\",\"title\":\"x\"}").with(userOf(orgB)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/knowledge/qa/{id}/validate", qaOfOrgB.getId())
                        .param("reviewerName", "x").with(userOf(orgB)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/knowledge/qa/{id}/publish", qaOfOrgB.getId())
                        .param("publisherName", "x").with(userOf(orgB)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/knowledge/qa/{id}/archive", qaOfOrgB.getId())
                        .with(userOf(orgB)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/knowledge/qa/{id}/benchmark-draft", qaOfOrgB.getId())
                        .with(userOf(orgB)))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // 2xx — ADMIN of the owning organization
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ADMIN: GET lista da propria organizacao → 200")
    void admin_list_200() throws Exception {
        mockMvc.perform(get("/api/v1/admin/knowledge/qa").with(adminOf(orgA)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADMIN: GET detalhe da propria organizacao → 200")
    void admin_ownDetail_200() throws Exception {
        mockMvc.perform(get("/api/v1/admin/knowledge/qa/{id}", qaOfOrgB.getId()).with(adminOf(orgB)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADMIN: pesquisa de semelhantes da propria organizacao → 200")
    void admin_similar_200() throws Exception {
        mockMvc.perform(get("/api/v1/admin/knowledge/qa/similar")
                        .param("question", "Qual a taxa ficticia?").with(adminOf(orgA)))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // 404 — cross-organization access is indistinguishable from a missing id
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ADMIN_A: GET detalhe de QA da Org B → 404 (nao revela existencia)")
    void crossOrg_detail_404() throws Exception {
        mockMvc.perform(get("/api/v1/admin/knowledge/qa/{id}", qaOfOrgB.getId()).with(adminOf(orgA)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("ADMIN_A: GET detalhe de id inexistente → 404 (mesma resposta do acesso cruzado)")
    void missingId_detail_404() throws Exception {
        mockMvc.perform(get("/api/v1/admin/knowledge/qa/{id}", UUID.randomUUID()).with(adminOf(orgA)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("ADMIN_A: alteracao, fonte, validate, publish e archive sobre QA da Org B → 404")
    void crossOrg_writes_404() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/knowledge/qa/{id}/curation", qaOfOrgB.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{}").with(adminOf(orgA)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/admin/knowledge/qa/{id}/sources", qaOfOrgB.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"OTHER\",\"title\":\"x\"}").with(adminOf(orgA)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/admin/knowledge/qa/{id}/validate", qaOfOrgB.getId())
                        .param("reviewerName", "x").with(adminOf(orgA)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/admin/knowledge/qa/{id}/publish", qaOfOrgB.getId())
                        .param("publisherName", "x").with(adminOf(orgA)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/admin/knowledge/qa/{id}/archive", qaOfOrgB.getId())
                        .with(adminOf(orgA)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/admin/knowledge/qa/{id}/benchmark-draft", qaOfOrgB.getId())
                        .with(adminOf(orgA)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("ADMIN_A: lista nao inclui QA da Org B")
    void crossOrg_listDoesNotLeak() throws Exception {
        mockMvc.perform(get("/api/v1/admin/knowledge/qa").with(adminOf(orgA)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.content[?(@.id=='%s')]".formatted(qaOfOrgB.getId())).isEmpty());
    }
}
