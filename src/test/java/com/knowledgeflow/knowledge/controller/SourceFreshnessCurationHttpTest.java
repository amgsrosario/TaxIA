package com.knowledgeflow.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.repository.AuditEventRepository;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.security.StaffAuthorities;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * C8 / ADR-008 sobre a cadeia de segurança real: só a curadoria de Q&amp;A (ADMIN) regista factos de
 * verificação; data e responsável vêm do servidor; o estado de actualidade é calculado e nunca
 * aceite do pedido; tudo auditado; permitido em Q&amp;A publicadas sem alterar estado nem publicação.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SourceFreshnessCurationHttpTest {

    private static final String BASE = "/api/v1/admin/knowledge/qa/{id}/sources/{sourceId}";

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired private KnowledgeSourceReferenceRepository sourceRepository;
    @Autowired private AuditEventRepository auditRepository;

    private Organization org;
    private Organization otherOrg;
    private KnowledgeQuestionAnswer qa;
    private KnowledgeSourceReference law;
    private KnowledgeSourceReference faq;
    private KnowledgeSourceReference opinion;
    private final UUID curator = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        org = organizationRepository.save(new Organization("Org C8", null));
        otherOrg = organizationRepository.save(new Organization("Org C8 outra", null));
        qa = qaRepository.save(new KnowledgeQuestionAnswer(org, "Pergunta C8?", "Resposta.", "c8-test", "C8-1"));
        law = source(KnowledgeSourceType.LEGISLATION, "Código do IVA — Artigo 36.º");
        faq = source(KnowledgeSourceType.OFFICIAL_FAQ, "Portal das Finanças — FAQ 1");
        opinion = source(KnowledgeSourceType.INTERNAL_OPINION, "Parecer interno");
    }

    private KnowledgeSourceReference source(KnowledgeSourceType type, String title) {
        KnowledgeSourceReference s = new KnowledgeSourceReference(qa, type, title);
        s.update(type, title, null, "https://exemplo.pt/" + type, null, null, null, null, null);
        return sourceRepository.save(s);
    }

    private RequestPostProcessor as(Organization o, String role, UUID subject) {
        return jwt().jwt(j -> j.subject(subject.toString()).claim("organization_id", o.getId().toString())
                        .claim("email", role.toLowerCase() + "@c8.test").claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority(StaffAuthorities.STAFF), new SimpleGrantedAuthority("ROLE_" + role));
    }

    private RequestPostProcessor admin() {
        return as(org, "ADMIN", curator);
    }

    // ------------------------------------------------------------ autorização

    @Test
    @DisplayName("Não autenticado → 401; papéis sem a capacidade de curadoria de Q&A (USER, VALIDATOR, AUTHOR) → 403")
    void authorization() throws Exception {
        mockMvc.perform(post(BASE + "/verification", qa.getId(), law.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"consolidatedVersionConfirmed\":true}"))
                .andExpect(status().isUnauthorized());
        for (String role : List.of("USER", "VALIDATOR", "AUTHOR", "VIEWER")) {
            mockMvc.perform(post(BASE + "/verification", qa.getId(), law.getId()).with(as(org, role, UUID.randomUUID()))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"consolidatedVersionConfirmed\":true}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put(BASE + "/stable-matter", qa.getId(), law.getId()).with(as(org, role, UUID.randomUUID()))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"stableMatter\":true,\"reason\":\"x\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete(BASE + "/verification", qa.getId(), law.getId()).with(as(org, role, UUID.randomUUID())))
                    .andExpect(status().isForbidden());
        }
        assertThat(sourceRepository.findById(law.getId()).orElseThrow().getVerifiedAt()).isNull();
    }

    @Test
    @DisplayName("ADMIN de outra organização → 404 (como id inexistente); fonte de outra Q&A → 404")
    void crossOrganizationIsNotFound() throws Exception {
        mockMvc.perform(post(BASE + "/verification", qa.getId(), law.getId()).with(as(otherOrg, "ADMIN", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"consolidatedVersionConfirmed\":true}"))
                .andExpect(status().isNotFound());
        KnowledgeQuestionAnswer other = qaRepository.save(new KnowledgeQuestionAnswer(org, "Outra?", "R.", "c8-test", "C8-2"));
        mockMvc.perform(post(BASE + "/verification", other.getId(), law.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"consolidatedVersionConfirmed\":true}"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ verificação

    @Test
    @DisplayName("Verificação de legislação com versão consolidada: CURRENT calculado, data e responsável do servidor, auditada")
    void verifyLegislation() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Lisbon"));
        mockMvc.perform(post(BASE + "/verification", qa.getId(), law.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        // campos extra (estado, data, responsável) são ignorados: nunca vêm do pedido
                        .content("{\"consolidatedVersionConfirmed\":true,\"freshnessStatus\":\"CURRENT\","
                                + "\"verifiedAt\":\"2020-01-01T00:00:00Z\",\"verifiedBy\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freshnessStatus").value("CURRENT"))
                .andExpect(jsonPath("$.verifiedBy").value(curator.toString()))
                .andExpect(jsonPath("$.verifiedAt").value(not(startsWith("2020"))))
                .andExpect(jsonPath("$.consolidatedVersionConfirmed").value(true))
                .andExpect(jsonPath("$.currentUntil").value(today.plusDays(90).toString()))
                .andExpect(jsonPath("$.revalidationNeeded").value(false));

        assertThat(auditRepository.findByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
                org.getId(), "KnowledgeQuestionAnswer", qa.getId()))
                .anySatisfy(e -> {
                    assertThat(e.getAction()).isEqualTo(AuditAction.KNOWLEDGE_QA_SOURCE_VERIFIED);
                    assertThat(e.getUserId()).isEqualTo(curator);
                    assertThat(e.getMetadata()).contains("sourceId=" + law.getId(), "consolidatedVersionConfirmed=true");
                });
    }

    @Test
    @DisplayName("Legislação sem versão consolidada confirmada: registada, mas UNCERTAIN")
    void legislationWithoutConsolidated_isUncertain() throws Exception {
        mockMvc.perform(post(BASE + "/verification", qa.getId(), law.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"consolidatedVersionConfirmed\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freshnessStatus").value("UNCERTAIN"));
    }

    @Test
    @DisplayName("FAQ oficial verificada → CURRENT 60 dias; consolidada numa FAQ → 400; fonte não elegível → 400")
    void faqAndEligibility() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Lisbon"));
        mockMvc.perform(post(BASE + "/verification", qa.getId(), faq.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"consolidatedVersionConfirmed\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freshnessStatus").value("CURRENT"))
                .andExpect(jsonPath("$.currentUntil").value(today.plusDays(60).toString()));
        mockMvc.perform(post(BASE + "/verification", qa.getId(), faq.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"consolidatedVersionConfirmed\":true}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE + "/verification", qa.getId(), opinion.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"consolidatedVersionConfirmed\":false}"))
                .andExpect(status().isBadRequest());
        assertThat(sourceRepository.findById(opinion.getId()).orElseThrow().getVerifiedAt()).isNull();
    }

    @Test
    @DisplayName("Revogar verificação → UNCERTAIN, auditada")
    void revoke() throws Exception {
        mockMvc.perform(post(BASE + "/verification", qa.getId(), law.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"consolidatedVersionConfirmed\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete(BASE + "/verification", qa.getId(), law.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freshnessStatus").value("UNCERTAIN"))
                .andExpect(jsonPath("$.verifiedAt").doesNotExist());
        assertThat(auditRepository.findByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
                org.getId(), "KnowledgeQuestionAnswer", qa.getId()))
                .anyMatch(e -> e.getAction() == AuditAction.KNOWLEDGE_QA_SOURCE_VERIFICATION_REVOKED);
    }

    // ------------------------------------------------------------ matéria estável

    @Test
    @DisplayName("Matéria estável: exige verificação prévia e justificação; marcada e desmarcada com auditoria")
    void stableMatter() throws Exception {
        mockMvc.perform(put(BASE + "/stable-matter", qa.getId(), law.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stableMatter\":true,\"reason\":\"Regra estável.\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE + "/verification", qa.getId(), law.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"consolidatedVersionConfirmed\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(put(BASE + "/stable-matter", qa.getId(), law.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stableMatter\":true,\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(BASE + "/stable-matter", qa.getId(), law.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stableMatter\":true,\"reason\":\"Regra estável desde 2013.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stableMatter").value(true))
                .andExpect(jsonPath("$.stableMatterMarkedBy").value(curator.toString()))
                // dentro da janela de 90 dias continua CURRENT: a marca não é um atalho
                .andExpect(jsonPath("$.freshnessStatus").value("CURRENT"));
        mockMvc.perform(put(BASE + "/stable-matter", qa.getId(), law.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stableMatter\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stableMatter").value(false))
                .andExpect(jsonPath("$.stableMatterReason").doesNotExist());
        assertThat(auditRepository.findByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
                org.getId(), "KnowledgeQuestionAnswer", qa.getId()))
                .filteredOn(e -> e.getAction() == AuditAction.KNOWLEDGE_QA_SOURCE_STABLE_MATTER_UPDATED).hasSize(2);
    }

    @Test
    @DisplayName("Revisão #3: revogar a verificação também retira a matéria estável (tem de ser reexaminada)")
    void revokeClearsStableMatter() throws Exception {
        mockMvc.perform(post(BASE + "/verification", qa.getId(), law.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"consolidatedVersionConfirmed\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(put(BASE + "/stable-matter", qa.getId(), law.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stableMatter\":true,\"reason\":\"Estável.\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete(BASE + "/verification", qa.getId(), law.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stableMatter").value(false))
                .andExpect(jsonPath("$.stableMatterReason").doesNotExist());
    }

    // ------------------------------------------------------------ Q&A publicada

    @Test
    @DisplayName("Q&A publicada: verificação permitida sem alterar estado de curadoria nem publicação (metadado de governação)")
    void publishedEntry_canBeVerified_withoutStateChange() throws Exception {
        qa.updateCuration(null, "Curta.", "Técnica.", com.knowledgeflow.knowledge.enums.KnowledgeTopic.IVA, null, "PT",
                com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel.LOW, false, null, null, null);
        qa.markPendingReview();
        qa.validate("Revisor");
        qa.markPublished("Publicador");
        qaRepository.saveAndFlush(qa);

        mockMvc.perform(post(BASE + "/verification", qa.getId(), law.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"consolidatedVersionConfirmed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freshnessStatus").value("CURRENT"));

        KnowledgeQuestionAnswer after = qaRepository.findById(qa.getId()).orElseThrow();
        assertThat(after.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
        assertThat(after.isPublished()).isTrue();
        assertThat(after.getTechnicalAnswer()).isEqualTo("Técnica.");
    }
}
