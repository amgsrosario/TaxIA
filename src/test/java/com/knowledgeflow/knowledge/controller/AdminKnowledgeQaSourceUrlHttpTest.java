package com.knowledgeflow.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
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
 * HTTP contract of source validation on POST /api/v1/admin/knowledge/qa/{id}/sources (URL rule and
 * field lengths), through the real controller, service and GlobalExceptionHandler. The full matrices
 * live in KnowledgeQuestionAnswerCurationServiceTest; this only proves how the rules reach the client.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminKnowledgeQaSourceUrlHttpTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired private KnowledgeSourceReferenceRepository sourceRepository;

    private Organization org;
    private KnowledgeQuestionAnswer qa;

    @BeforeEach
    void setUp() {
        org = organizationRepository.save(new Organization("Org Source URL", null));
        qa = qaRepository.save(new KnowledgeQuestionAnswer(
                org, "Pergunta ficticia?", "Resposta ficticia.", "url-test", "URL-001"));
    }

    @Test
    @DisplayName("URL com esquema não http/https → 400 VALIDATION_ERROR, nada persistido")
    void invalidSourceUrl_returns400ValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/admin/knowledge/qa/{id}/sources", qa.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"OFFICIAL_FAQ\",\"title\":\"FAQ\",\"url\":\"ftp://example.com\"}")
                        .with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.path").value("/api/v1/admin/knowledge/qa/" + qa.getId() + "/sources"));

        assertThat(sourceRepository.countByQuestionAnswerId(qa.getId())).isZero();
    }

    @Test
    @DisplayName("URL válida com espaços nas extremidades → 200 com o valor trimmed")
    void validSourceUrl_returnsTrimmedUrl() throws Exception {
        mockMvc.perform(post("/api/v1/admin/knowledge/qa/{id}/sources", qa.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"OFFICIAL_FAQ\",\"title\":\"FAQ\",\"url\":\"  https://example.com/x  \"}")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example.com/x"));
    }

    @Test
    @DisplayName("title com 300 caracteres (dentro do DTO e da V14) → 200 e persistido")
    void titleWithinContract_returns200AndPersists() throws Exception {
        String title = "t".repeat(300);

        mockMvc.perform(post("/api/v1/admin/knowledge/qa/{id}/sources", qa.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"OTHER\",\"title\":\"" + title + "\"}")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value(title));

        // The test transaction defers the INSERT: flush so the column length is really exercised.
        sourceRepository.flush();
        assertThat(sourceRepository.countByQuestionAnswerId(qa.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("title com 501 caracteres (acima do DTO) → 400 VALIDATION_ERROR")
    void titleAboveContract_returns400ValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/admin/knowledge/qa/{id}/sources", qa.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"OTHER\",\"title\":\"" + "t".repeat(501) + "\"}")
                        .with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.title").exists());

        assertThat(sourceRepository.countByQuestionAnswerId(qa.getId())).isZero();
    }

    private RequestPostProcessor admin() {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("organization_id", org.getId().toString())
                        .claim("email", "admin@url.test")
                        .claim("roles", List.of("ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
