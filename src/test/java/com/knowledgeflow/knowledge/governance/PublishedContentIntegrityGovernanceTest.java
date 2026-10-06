package com.knowledgeflow.knowledge.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgeflow.audit.entity.AuditEvent;
import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.repository.AuditEventRepository;
import com.knowledgeflow.knowledge.entity.KnowledgeQaApplicabilityExclusion;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.rag.KnowledgeQaEmbeddingIndexer;
import com.knowledgeflow.knowledge.repository.KnowledgeQaApplicabilityExclusionRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import com.knowledgeflow.security.StaffAuthorities;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integridade pós-validação (ADR-005) pelo HTTP real, com H2 e o indexador em mock: versão publicada
 * congelada, VALIDATED não publicada volta a revisão, versões em paralelo, publicar e substituir,
 * fontes, alterações conservadoras e optimistic lock.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublishedContentIntegrityGovernanceTest {

    private static final String QA = "/api/v1/admin/knowledge/qa/{id}";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired private KnowledgeSourceReferenceRepository sourceRepository;
    @Autowired private KnowledgeQaApplicabilityExclusionRepository exclusionRepository;
    @Autowired private AuditEventRepository auditRepository;
    @Autowired private EntityManager entityManager;
    @MockBean private KnowledgeQaEmbeddingIndexer indexer;

    private Organization org;
    private KnowledgeQuestionAnswer published;
    private KnowledgeQuestionAnswer validated;

    @BeforeEach
    void setUp() {
        org = organizationRepository.save(new Organization("Org Integridade", null));
        published = validatedWithSource("INT-PUB", 2);
        published.markPublished("Publicador");
        published = qaRepository.saveAndFlush(published);
        validated = validatedWithSource("INT-VAL", 1);
    }

    // ---- 37: versão publicada congelada para alterações materiais ---------------------------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"technicalAnswer", "shortAnswer", "normalizedQuestion", "topic", "subtopic", "jurisdiction"})
    @DisplayName("Publicada: alteração material → 409, nada muda, continua publicada e VALIDATED")
    void published_materialChange_isRejected(String field) throws Exception {
        Object value = field.equals("topic") ? "IVA" : "Valor alterado";
        patchCuration(published, Map.of(field, value))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value(containsString("new version")));

        KnowledgeQuestionAnswer after = reload(published);
        assertThat(after.getTechnicalAnswer()).isEqualTo("Resposta técnica INT-PUB.");
        assertThat(after.getTopic()).isEqualTo(KnowledgeTopic.IRS);
        assertThat(after.getSubtopic()).isEqualTo("Categoria F — gastos");
        assertThat(after.isPublished()).isTrue();
        assertThat(after.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
        assertThat(after.getReviewedBy()).isEqualTo("Revisor Inicial");
    }

    // ---- 42: alterações conservadoras e expansivas numa publicada ---------------------------

    @Test
    @DisplayName("Publicada: risco sobe, validação humana liga, validade encurta, notas → imediato e auditado")
    void published_conservativeChanges_applyImmediately() throws Exception {
        patchCuration(published, Map.of("riskLevel", "HIGH", "requiresHumanValidation", true,
                "validFrom", "2026-03-01", "validTo", "2027-06-30", "notes", "Nota nova."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.curationStatus").value("VALIDATED"))
                .andExpect(jsonPath("$.published").value(true));

        KnowledgeQuestionAnswer after = reload(published);
        assertThat(after.getRiskLevel()).isEqualTo(KnowledgeRiskLevel.HIGH);
        assertThat(after.isRequiresHumanValidation()).isTrue();
        assertThat(after.getValidTo()).isEqualTo(LocalDate.of(2027, 6, 30));
        assertThat(after.getReviewedBy()).isEqualTo("Revisor Inicial");
        assertThat(lastAudit(published, AuditAction.KNOWLEDGE_QA_UPDATED).getMetadata())
                .contains("changedFields=[riskLevel, requiresHumanValidation, validFrom, validTo, notes]",
                        "revalidationFields=[]", "published=true");
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "riskLevel,LOW", "validTo,2028-12-31", "validFrom,2025-01-01", "validTo,", "validFrom,"})
    @DisplayName("Publicada: alterações expansivas → 409 (nova versão)")
    void published_expansiveChanges_areRejected(String field, String value) throws Exception {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put(field, value);
        patchCuration(published, change).andExpect(status().isConflict());
        assertThat(reload(published).getRiskLevel()).isEqualTo(KnowledgeRiskLevel.MEDIUM);
    }

    @Test
    @DisplayName("Publicada: requiresHumanValidation true → false → 409")
    void published_humanValidationOff_isRejected() throws Exception {
        patchCuration(published, Map.of("requiresHumanValidation", true)).andExpect(status().isOk());
        patchCuration(reload(published), Map.of("requiresHumanValidation", false)).andExpect(status().isConflict());
        assertThat(reload(published).isRequiresHumanValidation()).isTrue();
    }

    // ---- 38: VALIDATED não publicada ---------------------------------------------------------

    @Test
    @DisplayName("VALIDATED não publicada + alteração material → PENDING_REVIEW, validação limpa, publish recusado")
    void validatedUnpublished_materialChange_returnsToReview() throws Exception {
        patchCuration(validated, Map.of("technicalAnswer", "Resposta técnica revista."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.curationStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.reviewedBy").doesNotExist());

        KnowledgeQuestionAnswer after = reload(validated);
        assertThat(after.getTechnicalAnswer()).isEqualTo("Resposta técnica revista.");
        assertThat(after.getReviewedAt()).isNull();
        assertThat(lastAudit(validated, AuditAction.KNOWLEDGE_QA_UPDATED).getMetadata())
                .contains("changedFields=[technicalAnswer]", "revalidationFields=[technicalAnswer]");
        assertThat(lastAudit(validated, AuditAction.KNOWLEDGE_QA_STATUS_CHANGED).getMetadata())
                .contains("previousStatus=VALIDATED newStatus=PENDING_REVIEW", "validationCleared=true");
        mockMvc.perform(post(QA + "/publish", validated.getId()).param("publisherName", "P").with(admin()))
                .andExpect(status().isBadRequest());
        verify(indexer, never()).index(eq(validated.getId()), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("VALIDATED não publicada + só notas → continua VALIDATED")
    void validatedUnpublished_freeChange_keepsValidation() throws Exception {
        patchCuration(validated, Map.of("notes", "Só uma nota.")).andExpect(status().isOk())
                .andExpect(jsonPath("$.curationStatus").value("VALIDATED"));
        assertThat(reload(validated).getReviewedBy()).isEqualTo("Revisor Inicial");
    }

    // ---- 11: optimistic lock ------------------------------------------------------------------

    @Test
    @DisplayName("Dois editores: o segundo, com versão antiga, recebe 409 e o primeiro conteúdo fica")
    void staleVersion_isRejected() throws Exception {
        int startVersion = reload(validated).getVersion();
        patchCuration(validated, Map.of("notes", "Primeiro editor."), startVersion).andExpect(status().isOk());
        patchCuration(validated, Map.of("notes", "Segundo editor."), startVersion).andExpect(status().isConflict());
        assertThat(reload(validated).getNotes()).isEqualTo("Primeiro editor.");

        mockMvc.perform(patch(QA + "/curation", validated.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"sem versão\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- M1 (revisão): a validação liga-se à versão lida ----------------------------------------

    @Test
    @DisplayName("Validar exige a versão lida: edição, fonte ou exclusão entretanto → 409; sem versão → 400")
    void validate_isBoundToTheVersionTheReviewerRead() throws Exception {
        KnowledgeQuestionAnswer draft = pendingWithSource("INT-REV");
        int read = reload(draft).getVersion();

        // outra pessoa edita o conteúdo depois da leitura
        patchCuration(draft, Map.of("technicalAnswer", "Editado por outro curador.")).andExpect(status().isOk());
        validate(draft, read).andExpect(status().isConflict());

        // uma fonte acrescentada depois da leitura também muda a versão
        int read2 = reload(draft).getVersion();
        mockMvc.perform(post(QA + "/sources", draft.getId()).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceType\":\"LEGISLATION\",\"title\":\"Outra fonte\"}")).andExpect(status().isOk());
        validate(draft, read2).andExpect(status().isConflict());

        // uma exclusão de aplicabilidade acrescentada depois da leitura também
        int read3 = reload(draft).getVersion();
        mockMvc.perform(post(QA + "/applicability/exclusions", draft.getId()).with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"marker\":\"INQUILINO\"}")).andExpect(status().isOk());
        validate(draft, read3).andExpect(status().isConflict());

        mockMvc.perform(post(QA + "/validate", draft.getId()).param("reviewerName", "Revisor").with(admin()))
                .andExpect(status().isBadRequest());
        assertThat(reload(draft).getCurationStatus()).isEqualTo(KnowledgeCurationStatus.PENDING_REVIEW);

        validate(draft, reload(draft).getVersion()).andExpect(status().isNoContent());
        assertThat(reload(draft).getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
    }

    @Test
    @DisplayName("Despublicar → editar → publicar: a edição devolve a revisão e a publicação é recusada")
    void unpublishEditPublish_requiresRevalidation() throws Exception {
        mockMvc.perform(post(QA + "/unpublish", published.getId()).with(admin())).andExpect(status().isNoContent());
        patchCuration(published, Map.of("technicalAnswer", "Alterada depois de despublicar.")).andExpect(status().isOk())
                .andExpect(jsonPath("$.curationStatus").value("PENDING_REVIEW"));
        mockMvc.perform(post(QA + "/publish", published.getId()).param("publisherName", "P").with(admin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Versões: outra organização → 404; sem ADMIN → 403; numeração _v3 e após rascunho rejeitado")
    void versions_accessAndKeyNumbering() throws Exception {
        Organization other = organizationRepository.save(new Organization("Outra org", null));
        RequestPostProcessor otherAdmin = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("organization_id", other.getId().toString()).claim("roles", List.of("ADMIN")))
                .authorities(new SimpleGrantedAuthority(StaffAuthorities.STAFF), new SimpleGrantedAuthority("ROLE_ADMIN"));
        RequestPostProcessor user = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("organization_id", org.getId().toString()).claim("roles", List.of("USER")))
                .authorities(new SimpleGrantedAuthority(StaffAuthorities.STAFF), new SimpleGrantedAuthority("ROLE_USER"));
        mockMvc.perform(post(QA + "/versions", published.getId()).with(otherAdmin)).andExpect(status().isNotFound());
        mockMvc.perform(post(QA + "/versions", published.getId()).with(user)).andExpect(status().isForbidden());

        UUID v2 = createVersion(published);
        mockMvc.perform(post(QA + "/publish-replacing/{prev}", v2, published.getId())
                        .param("publisherName", "P").with(otherAdmin)).andExpect(status().isNotFound());
        mockMvc.perform(post(QA + "/publish-replacing/{prev}", v2, published.getId())
                        .param("publisherName", "P").with(user)).andExpect(status().isForbidden());

        // rascunho rejeitado: a próxima versão não reutiliza a chave
        mockMvc.perform(post(QA + "/reject", v2).param("reviewerName", "Revisor").param("reason", "Não").with(admin()))
                .andExpect(status().isNoContent());
        UUID v3 = createVersion(published);
        assertThat(reload(qaRepository.findById(v3).orElseThrow()).getExternalKey()).isEqualTo("INT-PUB_v3");
    }

    // ---- 41: fontes -------------------------------------------------------------------------

    @Test
    @DisplayName("Publicada: adicionar fonte → 409; remover não final → ok; remover a última → bloqueado")
    void published_sources() throws Exception {
        mockMvc.perform(post(QA + "/sources", published.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"LEGISLATION\",\"title\":\"Fonte nova\"}"))
                .andExpect(status().isConflict());
        List<KnowledgeSourceReference> sources = sourceRepository.findByQuestionAnswerId(published.getId());
        assertThat(sources).hasSize(2);

        mockMvc.perform(delete(QA + "/sources/{s}", published.getId(), sources.get(0).getId()).with(admin()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete(QA + "/sources/{s}", published.getId(), sources.get(1).getId()).with(admin()))
                .andExpect(status().isBadRequest());
        // remover + adicionar não contorna: a adição continua recusada
        mockMvc.perform(post(QA + "/sources", published.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"LEGISLATION\",\"title\":\"Fonte trocada\",\"url\":\"https://x.pt\"}"))
                .andExpect(status().isConflict());
        KnowledgeQuestionAnswer after = reload(published);
        assertThat(after.isPublished()).isTrue();
        assertThat(after.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
    }

    @Test
    @DisplayName("VALIDATED não publicada: adicionar fonte → PENDING_REVIEW; remover fonte não final → continua VALIDATED")
    void validatedUnpublished_sources() throws Exception {
        KnowledgeQuestionAnswer two = validatedWithSource("INT-VAL2", 2);
        UUID first = sourceRepository.findByQuestionAnswerId(two.getId()).get(0).getId();
        mockMvc.perform(delete(QA + "/sources/{s}", two.getId(), first).with(admin())).andExpect(status().isNoContent());
        assertThat(reload(two).getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);

        mockMvc.perform(post(QA + "/sources", validated.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"LEGISLATION\",\"title\":\"Fonte nova\"}"))
                .andExpect(status().isOk());
        KnowledgeQuestionAnswer after = reload(validated);
        assertThat(after.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.PENDING_REVIEW);
        assertThat(after.getReviewedBy()).isNull();
    }

    // ---- 39/40: versões e publicar-e-substituir ---------------------------------------------

    @Test
    @DisplayName("Nova versão: cópia integral (fontes, exclusões), PENDING_REVIEW, não indexada; anterior intacta")
    void newVersion_copiesEverything_andKeepsPreviousPublished() throws Exception {
        exclusionRepository.save(new KnowledgeQaApplicabilityExclusion(published, "INQUILINO", "Só senhorio.", "curador"));
        KnowledgeQaApplicabilityExclusion pending =
                new KnowledgeQaApplicabilityExclusion(published, "HABITACAO_PROPRIA", null, "curador");
        pending.requestRemoval("curador");
        exclusionRepository.save(pending);
        published.markApplicabilityReviewed("Revisor Âmbito");
        published = qaRepository.saveAndFlush(published);

        String body = mockMvc.perform(post(QA + "/versions", published.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousVersionId").value(published.getId().toString()))
                .andExpect(jsonPath("$.curationStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.published").value(false))
                .andExpect(jsonPath("$.reviewedBy").doesNotExist())
                .andExpect(jsonPath("$.applicabilityReviewedAt").doesNotExist())
                .andExpect(jsonPath("$.externalKey").value("INT-PUB_v2"))
                .andReturn().getResponse().getContentAsString();
        UUID newId = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        KnowledgeQuestionAnswer draft = reload(qaRepository.findById(newId).orElseThrow());

        assertThat(draft.getTechnicalAnswer()).isEqualTo(published.getTechnicalAnswer());
        assertThat(draft.getShortAnswer()).isEqualTo(published.getShortAnswer());
        assertThat(draft.getSubtopic()).isEqualTo(published.getSubtopic());
        assertThat(draft.getTopic()).isEqualTo(published.getTopic());
        assertThat(draft.getRiskLevel()).isEqualTo(published.getRiskLevel());
        assertThat(draft.getValidTo()).isEqualTo(published.getValidTo());
        assertThat(draft.getNotes()).isEqualTo(published.getNotes());
        assertThat(sourceRepository.findByQuestionAnswerId(newId))
                .extracting(KnowledgeSourceReference::getTitle, KnowledgeSourceReference::getUrl,
                        KnowledgeSourceReference::getLegalReference, KnowledgeSourceReference::getSourceType)
                .containsExactlyInAnyOrderElementsOf(sourceRepository.findByQuestionAnswerId(published.getId()).stream()
                        .map(s -> tuple(s.getTitle(), s.getUrl(), s.getLegalReference(),
                                s.getSourceType())).toList());
        assertThat(sourceRepository.findByQuestionAnswerId(newId)).extracting(KnowledgeSourceReference::getId)
                .doesNotContainAnyElementsOf(sourceRepository.findByQuestionAnswerId(published.getId()).stream()
                        .map(KnowledgeSourceReference::getId).toList());
        assertThat(exclusionRepository.findByKnowledgeQaId(newId))
                .extracting(KnowledgeQaApplicabilityExclusion::getMarker)
                .containsExactlyInAnyOrder("INQUILINO", "HABITACAO_PROPRIA");
        assertThat(exclusionRepository.findByKnowledgeQaId(newId)).noneMatch(KnowledgeQaApplicabilityExclusion::isRemovalPending);

        KnowledgeQuestionAnswer previous = reload(published);
        assertThat(previous.isPublished()).isTrue();
        assertThat(previous.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
        verify(indexer, never()).remove(any());
        verify(indexer, never()).index(eq(newId), anyString(), anyString(), any());

        // uma só versão em preparação; versão de não publicada → 409
        mockMvc.perform(post(QA + "/versions", published.getId()).with(admin())).andExpect(status().isConflict());
        mockMvc.perform(post(QA + "/versions", validated.getId()).with(admin())).andExpect(status().isConflict());
        // a publicada mostra o rascunho
        mockMvc.perform(get(QA, published.getId()).with(admin()))
                .andExpect(jsonPath("$.draftVersionId").value(newId.toString()));
    }

    @Test
    @DisplayName("Publicar e substituir: só VALIDATED; publish simples recusado; troca atómica auditada")
    void publishReplacing() throws Exception {
        UUID newId = createVersion(published);
        patchCuration(qaRepository.findById(newId).orElseThrow(), Map.of("technicalAnswer", "Resposta técnica v2."))
                .andExpect(status().isOk());

        // ainda PENDING_REVIEW → recusado; publish simples → 409 (outra versão publicada)
        mockMvc.perform(post(QA + "/publish-replacing/{prev}", newId, published.getId())
                        .param("publisherName", "Publicador").with(admin()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(QA + "/validate", newId).param("reviewerName", "Revisor v2")
                        .param("expectedVersion", String.valueOf(reload(qaRepository.findById(newId).orElseThrow()).getVersion()))
                        .with(admin()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(QA + "/publish", newId).param("publisherName", "Publicador").with(admin()))
                .andExpect(status().isConflict());
        // par errado → 409
        mockMvc.perform(post(QA + "/publish-replacing/{prev}", newId, validated.getId())
                        .param("publisherName", "Publicador").with(admin()))
                .andExpect(status().isConflict());

        mockMvc.perform(post(QA + "/publish-replacing/{prev}", newId, published.getId())
                        .param("publisherName", "Publicador").with(admin()))
                .andExpect(status().isNoContent());

        KnowledgeQuestionAnswer v2 = reload(qaRepository.findById(newId).orElseThrow());
        KnowledgeQuestionAnswer v1 = reload(published);
        assertThat(v2.isPublished()).isTrue();
        assertThat(v1.isPublished()).isFalse();
        verify(indexer).index(eq(newId), anyString(), eq("Resposta técnica v2."), any());
        verify(indexer).remove(published.getId());
        assertThat(lastAudit(v2, AuditAction.KNOWLEDGE_QA_PUBLISHED).getMetadata())
                .contains("replaces=" + published.getId());
        assertThat(lastAudit(v1, AuditAction.KNOWLEDGE_QA_UNPUBLISHED).getMetadata())
                .contains("replacedBy=" + newId);
        // republicar a antiga também é recusado (a nova está publicada)
        mockMvc.perform(post(QA + "/publish", published.getId()).param("publisherName", "P").with(admin()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Reindex: publicada VALIDATED ok; publicada fora de VALIDATED recusada; rascunho recusado")
    void reindexGuards() throws Exception {
        mockMvc.perform(post(QA + "/reindex", published.getId()).with(admin())).andExpect(status().isNoContent());
        UUID newId = createVersion(published);
        mockMvc.perform(post(QA + "/reindex", newId).with(admin())).andExpect(status().isConflict());
        mockMvc.perform(post(QA + "/outdated", published.getId()).with(admin())).andExpect(status().isNoContent());
        mockMvc.perform(post(QA + "/reindex", published.getId()).with(admin())).andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------------------------

    private UUID createVersion(KnowledgeQuestionAnswer qa) throws Exception {
        String body = mockMvc.perform(post(QA + "/versions", qa.getId()).with(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private ResultActions patchCuration(KnowledgeQuestionAnswer qa, Map<String, Object> overrides) throws Exception {
        return patchCuration(qa, overrides, reload(qa).getVersion());
    }

    private ResultActions patchCuration(KnowledgeQuestionAnswer qa, Map<String, Object> overrides, int version)
            throws Exception {
        KnowledgeQuestionAnswer current = reload(qa);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("normalizedQuestion", current.getNormalizedQuestion());
        body.put("shortAnswer", current.getShortAnswer());
        body.put("technicalAnswer", current.getTechnicalAnswer());
        body.put("topic", current.getTopic() == null ? null : current.getTopic().name());
        body.put("subtopic", current.getSubtopic());
        body.put("jurisdiction", current.getJurisdiction());
        body.put("riskLevel", current.getRiskLevel().name());
        body.put("requiresHumanValidation", current.isRequiresHumanValidation());
        body.put("validFrom", current.getValidFrom() == null ? null : current.getValidFrom().toString());
        body.put("validTo", current.getValidTo() == null ? null : current.getValidTo().toString());
        body.put("notes", current.getNotes());
        body.putAll(overrides);
        body.put("expectedVersion", version);
        return mockMvc.perform(patch(QA + "/curation", qa.getId()).with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions validate(KnowledgeQuestionAnswer qa, int expectedVersion) throws Exception {
        return mockMvc.perform(post(QA + "/validate", qa.getId()).param("reviewerName", "Revisor")
                .param("expectedVersion", String.valueOf(expectedVersion)).with(admin()));
    }

    private KnowledgeQuestionAnswer pendingWithSource(String key) {
        KnowledgeQuestionAnswer qa = new KnowledgeQuestionAnswer(org, "Pergunta " + key + "?", "Original.", "int-test", key);
        qa.updateCuration(null, "Curta " + key + ".", "Técnica " + key + ".", KnowledgeTopic.IRS, "Categoria F", "PT",
                KnowledgeRiskLevel.MEDIUM, false, null, null, null);
        qa.markPendingReview();
        qa = qaRepository.save(qa);
        sourceRepository.save(new KnowledgeSourceReference(qa, KnowledgeSourceType.LEGISLATION, "CIRS"));
        return qaRepository.saveAndFlush(qa);
    }

    private KnowledgeQuestionAnswer reload(KnowledgeQuestionAnswer qa) {
        entityManager.flush();
        entityManager.clear();
        return qaRepository.findById(qa.getId()).orElseThrow();
    }

    private AuditEvent lastAudit(KnowledgeQuestionAnswer qa, AuditAction action) {
        return auditRepository.findByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
                        org.getId(), "KnowledgeQuestionAnswer", qa.getId()).stream()
                .filter(e -> e.getAction() == action).findFirst().orElseThrow();
    }

    private KnowledgeQuestionAnswer validatedWithSource(String key, int sources) {
        KnowledgeQuestionAnswer qa = new KnowledgeQuestionAnswer(org, "Que despesas posso deduzir às rendas?",
                "Resposta original.", "int-test", key);
        qa.updateCuration("Que despesas posso deduzir às rendas?", "Resposta curta " + key + ".",
                "Resposta técnica " + key + ".", KnowledgeTopic.IRS, "Categoria F — gastos", "PT",
                KnowledgeRiskLevel.MEDIUM, false, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), "Nota.");
        qa.markPendingReview();
        qa = qaRepository.save(qa);
        for (int i = 1; i <= sources; i++) {
            KnowledgeSourceReference ref = new KnowledgeSourceReference(qa, KnowledgeSourceType.LEGISLATION, "CIRS " + i);
            ref.update(KnowledgeSourceType.LEGISLATION, "CIRS " + i, "art. 41.º", "https://info.gov.pt/" + i,
                    null, null, null, null, null);
            sourceRepository.save(ref);
        }
        qa.validate("Revisor Inicial");
        return qaRepository.saveAndFlush(qa);
    }

    private RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("organization_id", org.getId().toString())
                        .claim("email", "curador@integridade.test")
                        .claim("roles", List.of("ADMIN")))
                .authorities(new SimpleGrantedAuthority(StaffAuthorities.STAFF), new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
