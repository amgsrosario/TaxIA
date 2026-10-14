package com.knowledgeflow.knowledge.applicability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knowledgeflow.ai.grounding.scope.FiscalScopeFilter;
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
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerPublicationService;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import com.knowledgeflow.rag.RagSearchService.SourceKind;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import com.knowledgeflow.security.StaffAuthorities;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Governação das exclusões de aplicabilidade (M4-SCOPE-V2, ADR-004) pelo HTTP real (controller,
 * serviço, GlobalExceptionHandler), com H2 e o gate de produção. Q&amp;A descartáveis; o indexador é
 * um mock para provar que nenhuma operação reindexa.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class KnowledgeQaApplicabilityGovernanceTest {

    private static final String BASE = "/api/v1/admin/knowledge/qa/{id}/applicability";
    private static final String TENANT_QUESTION = "Como inquilino, posso abater as obras que paguei no IRS?";

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired private KnowledgeQaApplicabilityExclusionRepository exclusionRepository;
    @Autowired private KnowledgeSourceReferenceRepository sourceRepository;
    @Autowired private AuditEventRepository auditRepository;
    @Autowired private FiscalScopeFilter scopeFilter;
    @Autowired private EntityManager entityManager;
    @Autowired private KnowledgeQuestionAnswerPublicationService publicationService;
    @MockBean private KnowledgeQaEmbeddingIndexer indexer;

    private Organization org;
    private KnowledgeQuestionAnswer published;
    private KnowledgeQuestionAnswer validatedUnpublished;
    private KnowledgeQuestionAnswer draft;

    @BeforeEach
    void setUp() {
        org = organizationRepository.save(new Organization("Org Aplicabilidade", null));
        published = qa("APL-PUB", KnowledgeCurationStatus.VALIDATED, true);
        validatedUnpublished = qa("APL-VAL", KnowledgeCurationStatus.VALIDATED, false);
        draft = qa("APL-DRAFT", KnowledgeCurationStatus.PENDING_REVIEW, false);
    }

    // ---- adicionar ----------------------------------------------------------------------------

    @Test
    @DisplayName("Adicionar em Q&A publicada: efectiva já, auditada, continua publicada, sem reindex nem nova validação")
    void addOnPublished_isEffectiveImmediately() throws Exception {
        assertThat(gateKeeps(published, TENANT_QUESTION)).isTrue();

        mockMvc.perform(post(BASE + "/exclusions", published.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marker\":\"INQUILINO\",\"note\":\"Resposta só para o senhorio.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exclusions[0].marker").value("INQUILINO"))
                .andExpect(jsonPath("$.exclusions[0].label").value("Inquilino / arrendatário"))
                .andExpect(jsonPath("$.exclusions[0].note").value("Resposta só para o senhorio."))
                .andExpect(jsonPath("$.exclusions[0].createdBy").value("curador@aplic.test"))
                .andExpect(jsonPath("$.exclusions[0].removalPending").value(false))
                .andExpect(jsonPath("$.derivedScope.incomeCategories[0]").value("F"));

        KnowledgeQuestionAnswer after = qaRepository.findById(published.getId()).orElseThrow();
        assertThat(after.isPublished()).isTrue();
        assertThat(after.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
        assertThat(gateKeeps(published, TENANT_QUESTION)).isFalse();
        assertThat(gateKeeps(published, "Sou senhorio; o condomínio do andar arrendado abate às rendas?")).isTrue();
        assertThat(lastAudit(published).getMetadata())
                .contains("event=ADDED", "marker=INQUILINO", "published=true", "actor=curador@aplic.test");
        verifyNoInteractions(indexer);
    }

    @Test
    @DisplayName("Marcador inválido → 400, nada persistido; duplicado → 409; nota > 500 → 400")
    void invalidInput_isRejected() throws Exception {
        mockMvc.perform(post(BASE + "/exclusions", draft.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"marker\":\"SENHORIO_ESPECULATIVO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post(BASE + "/exclusions", draft.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marker\":\"INQUILINO\",\"note\":\"" + "n".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(exclusionRepository.findByKnowledgeQaId(draft.getId())).isEmpty();

        add(draft, "INQUILINO");
        mockMvc.perform(post(BASE + "/exclusions", draft.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"marker\":\"INQUILINO\"}"))
                .andExpect(status().isConflict());
        assertThat(exclusionRepository.findByKnowledgeQaId(draft.getId())).hasSize(1);
    }

    // ---- remover ------------------------------------------------------------------------------

    @Test
    @DisplayName("Remover em Q&A não publicada nem validada: efectiva de imediato e auditada")
    void removeOnUnpublished_isImmediate() throws Exception {
        add(draft, "INQUILINO");
        assertThat(gateKeeps(draft, TENANT_QUESTION)).isFalse();

        mockMvc.perform(delete(BASE + "/exclusions/{marker}", draft.getId(), "INQUILINO").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exclusions").isEmpty());

        assertThat(exclusionRepository.findByKnowledgeQaId(draft.getId())).isEmpty();
        assertThat(gateKeeps(draft, TENANT_QUESTION)).isTrue();
        assertThat(lastAudit(draft).getMetadata()).contains("event=REMOVED", "marker=INQUILINO", "published=false",
                "curationStatus=PENDING_REVIEW");
    }

    @Test
    @DisplayName("Q&A VALIDATED não publicada: a remoção fica pendente (fecha despublicar → remover → republicar)")
    void removeOnValidatedUnpublished_isPending() throws Exception {
        add(validatedUnpublished, "INQUILINO");

        mockMvc.perform(delete(BASE + "/exclusions/{marker}", validatedUnpublished.getId(), "INQUILINO").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removalRequiresValidation").value(true))
                .andExpect(jsonPath("$.exclusions[0].removalPending").value(true));

        assertThat(gateKeeps(validatedUnpublished, TENANT_QUESTION)).isFalse();
    }

    @Test
    @DisplayName("Remover em Q&A publicada: fica pendente, a exclusão continua efectiva; só a aprovação humana a remove")
    void removeOnPublished_staysEffectiveUntilHumanApproval() throws Exception {
        add(published, "INQUILINO");

        // pedido de remoção (é o único efeito possível do DELETE numa publicada: sem bypass)
        mockMvc.perform(delete(BASE + "/exclusions/{marker}", published.getId(), "INQUILINO").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exclusions[0].marker").value("INQUILINO"))
                .andExpect(jsonPath("$.exclusions[0].removalPending").value(true))
                .andExpect(jsonPath("$.exclusions[0].removalRequestedBy").value("curador@aplic.test"));
        // um segundo DELETE não contorna o pedido pendente (409) e nada muda
        mockMvc.perform(delete(BASE + "/exclusions/{marker}", published.getId(), "INQUILINO").with(admin()))
                .andExpect(status().isConflict());

        assertThat(exclusionRepository.findByKnowledgeQaId(published.getId())).hasSize(1);
        assertThat(gateKeeps(published, TENANT_QUESTION)).isFalse();
        assertThat(lastAudit(published).getMetadata()).contains("event=REMOVAL_REQUESTED", "marker=INQUILINO");

        // aprovação sem revisor → 400; nada muda
        mockMvc.perform(post(BASE + "/exclusions/{marker}/approve-removal", published.getId(), "INQUILINO")
                        .param("reviewerName", " ").with(admin()))
                .andExpect(status().isBadRequest());
        assertThat(gateKeeps(published, TENANT_QUESTION)).isFalse();

        // validação humana
        mockMvc.perform(post(BASE + "/exclusions/{marker}/approve-removal", published.getId(), "INQUILINO")
                        .param("reviewerName", "Revisor Humano").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exclusions").isEmpty());

        KnowledgeQuestionAnswer after = qaRepository.findById(published.getId()).orElseThrow();
        assertThat(after.isPublished()).isTrue();
        assertThat(after.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
        assertThat(gateKeeps(published, TENANT_QUESTION)).isTrue();
        assertThat(lastAudit(published).getMetadata()).contains("event=REMOVAL_APPROVED", "marker=INQUILINO",
                "actor=Revisor Humano", "requestedBy=curador@aplic.test");
        verifyNoInteractions(indexer);
    }

    @Test
    @DisplayName("Cancelar pedido de remoção: a exclusão mantém-se; aprovar sem pedido → 409")
    void cancelRemoval_keepsExclusion_andApprovalNeedsAPendingRequest() throws Exception {
        add(published, "HABITACAO_PROPRIA");
        mockMvc.perform(post(BASE + "/exclusions/{marker}/approve-removal", published.getId(), "HABITACAO_PROPRIA")
                        .param("reviewerName", "Revisor").with(admin()))
                .andExpect(status().isConflict());

        mockMvc.perform(delete(BASE + "/exclusions/{marker}", published.getId(), "HABITACAO_PROPRIA").with(admin()))
                .andExpect(jsonPath("$.exclusions[0].removalPending").value(true));
        mockMvc.perform(post(BASE + "/exclusions/{marker}/cancel-removal", published.getId(), "HABITACAO_PROPRIA")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exclusions[0].removalPending").value(false));

        assertThat(exclusionRepository.findByKnowledgeQaId(published.getId())).hasSize(1);
        assertThat(lastAudit(published).getMetadata()).contains("event=REMOVAL_CANCELLED");
        mockMvc.perform(delete(BASE + "/exclusions/{marker}", published.getId(), "INQUILINO").with(admin()))
                .andExpect(status().isNotFound());
    }

    // ---- revisão e legacy ---------------------------------------------------------------------

    @Test
    @DisplayName("Marcar âmbito revisto com zero exclusões: grava quem/quando e audita; legacy sem revisão é válido")
    void markReviewed_withoutExclusions() throws Exception {
        mockMvc.perform(get(BASE, published.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicabilityReviewedAt").doesNotExist())
                .andExpect(jsonPath("$.vocabulary.length()").value(12));

        mockMvc.perform(post(BASE + "/reviewed", published.getId()).param("reviewerName", "Revisor").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicabilityReviewedBy").value("Revisor"))
                .andExpect(jsonPath("$.applicabilityReviewedAt").exists())
                .andExpect(jsonPath("$.exclusions").isEmpty());

        assertThat(lastAudit(published).getMetadata()).contains("event=REVIEWED", "exclusions=[]");

        // uma alteração efectiva das exclusões repõe "não revisto"
        mockMvc.perform(post(BASE + "/exclusions", published.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"marker\":\"INQUILINO\",\"note\":\"Diz \\\"só senhorio\\\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicabilityReviewedAt").doesNotExist());
        assertThat(lastAudit(published).getMetadata()).endsWith("note=\"Diz \\\"só senhorio\\\"\"");
        // a Q&A nunca revista continua elegível para RAG e o detalhe mostra o estado
        assertThat(draft.getApplicabilityReviewedAt()).isNull();
        assertThat(published.isEligibleForRag()).isTrue();
        mockMvc.perform(get("/api/v1/admin/knowledge/qa/{id}", draft.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicabilityReviewedAt").doesNotExist())
                .andExpect(jsonPath("$.applicabilityExclusions").isEmpty());
    }

    @Test
    @DisplayName("Nova versão herda as exclusões (pendentes ficam efectivas), não a marca de revisão")
    void newVersion_inheritsExclusions() throws Exception {
        add(published, "INQUILINO");
        add(published, "HABITACAO_PROPRIA");
        mockMvc.perform(delete(BASE + "/exclusions/{marker}", published.getId(), "HABITACAO_PROPRIA").with(admin()))
                .andExpect(status().isOk());
        assertThat(exclusionRepository.findByKnowledgeQaIdAndMarker(published.getId(), "HABITACAO_PROPRIA")
                .orElseThrow().isRemovalPending()).isTrue();
        mockMvc.perform(post(BASE + "/reviewed", published.getId()).param("reviewerName", "Revisor").with(admin()));
        sourceRepository.save(new KnowledgeSourceReference(published, KnowledgeSourceType.LEGISLATION, "CIRS, art. 41.º"));

        KnowledgeQuestionAnswer next = publicationService.createNewVersion(
                org.getId(), null, "Publicador", published.getId(), "Resposta técnica revista.");

        assertThat(exclusionRepository.findByKnowledgeQaId(next.getId()))
                .extracting(KnowledgeQaApplicabilityExclusion::getMarker)
                .containsExactlyInAnyOrder("INQUILINO", "HABITACAO_PROPRIA");
        assertThat(exclusionRepository.findByKnowledgeQaId(next.getId()))
                .noneMatch(KnowledgeQaApplicabilityExclusion::isRemovalPending);
        assertThat(next.getApplicabilityReviewedAt()).isNull();
        assertThat(auditRepository.findByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
                        org.getId(), "KnowledgeQuestionAnswer", next.getId()))
                .anySatisfy(e -> assertThat(e.getMetadata()).contains("event=INHERITED", "INQUILINO", "HABITACAO_PROPRIA"));
    }

    // ---- contexto obrigatório (ADR-009) ------------------------------------------------------

    private static final String ISOLATED_ACT_QUESTION =
            "Num acto isolado, que despesas podem ser deduzidas aos rendimentos prediais?";

    @Test
    @DisplayName("ADR-009: contexto obrigatório em Q&A publicada é imediato; sem o contexto na pergunta o gate rejeita")
    void requiredContext_onPublished_isImmediate() throws Exception {
        assertThat(gateKeeps(published, TENANT_QUESTION.replace("Como inquilino, ", ""))).isTrue();

        mockMvc.perform(post(BASE + "/exclusions", published.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marker\":\"ACTO_ISOLADO\",\"kind\":\"REQUIRES\",\"note\":\"Só acto isolado.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exclusions[0].marker").value("ACTO_ISOLADO"))
                .andExpect(jsonPath("$.exclusions[0].kind").value("REQUIRES"))
                .andExpect(jsonPath("$.exclusions[0].label").value("Acto isolado"));

        KnowledgeQuestionAnswer after = qaRepository.findById(published.getId()).orElseThrow();
        assertThat(after.isPublished()).isTrue();
        assertThat(after.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
        assertThat(gateKeeps(published, "Que despesas podem ser deduzidas aos rendimentos prediais?")).isFalse();
        assertThat(gateKeeps(published, ISOLATED_ACT_QUESTION)).isTrue();
        assertThat(gateKeeps(published, "Não é um acto isolado; que despesas podem ser deduzidas às rendas?")).isFalse();
        assertThat(lastAudit(published).getMetadata())
                .contains("event=ADDED", "marker=ACTO_ISOLADO", "kind=REQUIRES", "published=true");
        verifyNoInteractions(indexer);
    }

    @Test
    @DisplayName("ADR-009: tipo omisso é exclusão (formato de auditoria inalterado); tipo desconhecido → 400")
    void ruleKind_defaultAndValidation() throws Exception {
        add(draft, "INQUILINO");
        assertThat(exclusionRepository.findByKnowledgeQaIdAndMarker(draft.getId(), "INQUILINO").orElseThrow().getKind())
                .isEqualTo("EXCLUDES");
        assertThat(lastAudit(draft).getMetadata()).doesNotContain("kind=");

        mockMvc.perform(post(BASE + "/exclusions", draft.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"marker\":\"ACTO_ISOLADO\",\"kind\":\"PREFERS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(exclusionRepository.findByKnowledgeQaIdAndMarker(draft.getId(), "ACTO_ISOLADO")).isEmpty();
    }

    @Test
    @DisplayName("ADR-009: só marcadores afinados para precisão podem ser contexto obrigatório; o vocabulário indica-o")
    void requiredContext_onlyForRequirableMarkers() throws Exception {
        mockMvc.perform(post(BASE + "/exclusions", draft.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"marker\":\"INQUILINO\",\"kind\":\"REQUIRES\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(exclusionRepository.findByKnowledgeQaId(draft.getId())).isEmpty();

        mockMvc.perform(get(BASE, draft.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vocabulary[?(@.code=='INQUILINO')].requirable").value(false))
                .andExpect(jsonPath("$.vocabulary[?(@.code=='ACTO_ISOLADO')].requirable").value(true));
    }

    @Test
    @DisplayName("ADR-009: nova versão com tipo de regra desconhecido falha em vez de o reescrever como exclusão")
    void newVersion_unknownKind_failsInsteadOfWidening() throws Exception {
        addRequired(published, "ACTO_ISOLADO");
        sourceRepository.save(new KnowledgeSourceReference(published, KnowledgeSourceType.LEGISLATION, "CIRS, art. 41.º"));
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE knowledge_qa_applicability_exclusions SET kind = 'PREFERS' WHERE marker = 'ACTO_ISOLADO'")
                .executeUpdate();
        entityManager.clear();

        assertThatThrownBy(() -> publicationService.createNewVersion(
                        org.getId(), null, "Publicador", published.getId(), "Resposta técnica revista."))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PREFERS");
    }

    @Test
    @DisplayName("ADR-009: retirar contexto obrigatório de uma publicada alarga o âmbito → pendente até aprovação humana")
    void requiredContext_removalOnPublished_needsApproval() throws Exception {
        addRequired(published, "ACTO_ISOLADO");
        mockMvc.perform(delete(BASE + "/exclusions/{marker}", published.getId(), "ACTO_ISOLADO").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exclusions[0].removalPending").value(true));
        assertThat(gateKeeps(published, "Que despesas podem ser deduzidas aos rendimentos prediais?")).isFalse();
        assertThat(lastAudit(published).getMetadata()).contains("event=REMOVAL_REQUESTED", "kind=REQUIRES");

        mockMvc.perform(post(BASE + "/exclusions/{marker}/approve-removal", published.getId(), "ACTO_ISOLADO")
                        .param("reviewerName", "Especialista").with(admin()))
                .andExpect(status().isOk());
        assertThat(gateKeeps(published, "Que despesas podem ser deduzidas aos rendimentos prediais?")).isTrue();
        assertThat(lastAudit(published).getMetadata()).contains("event=REMOVAL_APPROVED", "kind=REQUIRES");
    }

    @Test
    @DisplayName("ADR-009: nova versão herda o contexto obrigatório com o tipo")
    void newVersion_inheritsRequiredContext() throws Exception {
        add(published, "INQUILINO");
        addRequired(published, "ACTO_ISOLADO");
        sourceRepository.save(new KnowledgeSourceReference(published, KnowledgeSourceType.LEGISLATION, "CIRS, art. 41.º"));

        KnowledgeQuestionAnswer next = publicationService.createNewVersion(
                org.getId(), null, "Publicador", published.getId(), "Resposta técnica revista.");

        assertThat(exclusionRepository.findByKnowledgeQaIdAndMarker(next.getId(), "ACTO_ISOLADO").orElseThrow().getKind())
                .isEqualTo("REQUIRES");
        assertThat(exclusionRepository.findByKnowledgeQaIdAndMarker(next.getId(), "INQUILINO").orElseThrow().getKind())
                .isEqualTo("EXCLUDES");
        assertThat(auditRepository.findByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
                        org.getId(), "KnowledgeQuestionAnswer", next.getId()))
                .anySatisfy(e -> assertThat(e.getMetadata()).contains("event=INHERITED", "ACTO_ISOLADO(REQUIRES)", "INQUILINO"));
    }

    @Test
    @DisplayName("ADR-009: exclusão SUBLOCACAO rejeita o subarrendamento e mantém o senhorio")
    void sublocacaoExclusion() throws Exception {
        add(published, "SUBLOCACAO");
        assertThat(gateKeeps(published, "Sou subarrendatário. Que despesas posso deduzir às rendas do subarrendamento?"))
                .isFalse();
        assertThat(gateKeeps(published, "Que despesas podem ser deduzidas aos rendimentos prediais obtidos com o arrendamento?"))
                .isTrue();
    }

    @Test
    @DisplayName("reviewerName acima de 255 caracteres → 400")
    void reviewerNameTooLong() throws Exception {
        mockMvc.perform(post(BASE + "/reviewed", published.getId()).param("reviewerName", "r".repeat(256)).with(admin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Outra organização → 404; sem ADMIN → 403")
    void accessControl() throws Exception {
        Organization other = organizationRepository.save(new Organization("Outra org", null));
        mockMvc.perform(post(BASE + "/exclusions", published.getId()).with(adminOf(other))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"marker\":\"INQUILINO\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(BASE + "/exclusions", published.getId())
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                                .claim("organization_id", org.getId().toString())
                                .claim("roles", List.of("USER")))
                                .authorities(new SimpleGrantedAuthority(StaffAuthorities.STAFF), new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"marker\":\"INQUILINO\"}"))
                .andExpect(status().isForbidden());
        assertThat(exclusionRepository.findByKnowledgeQaId(published.getId())).isEmpty();
    }

    @Test
    @DisplayName("Apagar a Q&A apaga as exclusões (ON DELETE CASCADE / sem órfãos)")
    void exclusionsFollowTheQa() throws Exception {
        add(draft, "INQUILINO");
        entityManager.flush();
        entityManager.createNativeQuery("DELETE FROM knowledge_question_answers WHERE id = :id")
                .setParameter("id", draft.getId()).executeUpdate();
        entityManager.clear();

        assertThat(exclusionRepository.findAll())
                .extracting(e -> e.getQuestionAnswer().getId()).doesNotContain(draft.getId());
    }

    // -------------------------------------------------------------------------------------------

    private boolean gateKeeps(KnowledgeQuestionAnswer qa, String question) {
        entityManager.flush();
        return !scopeFilter.filter(question, List.of(new RetrievedCase("Q&A", "?", "Resposta.", 0.95,
                SourceKind.KNOWLEDGE_QA, qa.getId()))).isEmpty();
    }

    private void add(KnowledgeQuestionAnswer qa, String marker) throws Exception {
        mockMvc.perform(post(BASE + "/exclusions", qa.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"marker\":\"" + marker + "\"}"))
                .andExpect(status().isOk());
    }

    private void addRequired(KnowledgeQuestionAnswer qa, String marker) throws Exception {
        mockMvc.perform(post(BASE + "/exclusions", qa.getId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marker\":\"" + marker + "\",\"kind\":\"REQUIRES\"}"))
                .andExpect(status().isOk());
    }

    private AuditEvent lastAudit(KnowledgeQuestionAnswer qa) {
        return auditRepository.findByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
                        org.getId(), "KnowledgeQuestionAnswer", qa.getId()).stream()
                .filter(e -> e.getAction() == AuditAction.KNOWLEDGE_QA_APPLICABILITY_UPDATED)
                .findFirst().orElseThrow();
    }

    private KnowledgeQuestionAnswer qa(String key, KnowledgeCurationStatus status, boolean publish) {
        KnowledgeQuestionAnswer qa = new KnowledgeQuestionAnswer(org,
                "Que despesas podem ser deduzidas aos rendimentos prediais obtidos com o arrendamento?",
                "Resposta.", "aplic-test", key);
        qa.updateCuration(null, "Resposta curta.", "Resposta técnica.", KnowledgeTopic.IRS,
                "Categoria F — gastos dedutíveis", "PT", KnowledgeRiskLevel.MEDIUM, false, null, null, null);
        qa.markPendingReview();
        if (status == KnowledgeCurationStatus.VALIDATED) {
            qa.validate("Revisor Inicial");
        }
        if (publish) {
            qa.markPublished("Publicador");
        }
        return qaRepository.save(qa);
    }

    private RequestPostProcessor admin() {
        return adminOf(org);
    }

    private static RequestPostProcessor adminOf(Organization organization) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("organization_id", organization.getId().toString())
                        .claim("email", "curador@aplic.test")
                        .claim("roles", List.of("ADMIN")))
                .authorities(new SimpleGrantedAuthority(StaffAuthorities.STAFF), new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
