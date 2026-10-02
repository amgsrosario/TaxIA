package com.knowledgeflow.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.audit.repository.AuditEventRepository;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.knowledge.dto.SourceReferenceRequest;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class KnowledgeQuestionAnswerPublicationServiceTest {

    @Autowired private KnowledgeQuestionAnswerPublicationService publicationService;
    @Autowired private KnowledgeQuestionAnswerCurationService curationService;
    @Autowired private KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private KnowledgeSourceReferenceRepository sourceRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private AuditEventRepository auditEventRepository;

    private Organization org;
    private UUID userId;

    @BeforeEach
    void setUp() {
        org = organizationRepository.save(new Organization("TaxIA Pub Test", null));
        userId = UUID.randomUUID();
    }

    // 19. publish() → publishedAt preenchido
    @Test
    void publish_eligibleEntry_setsPublishedAt() {
        KnowledgeQuestionAnswer qa = savedValidatedQaWithSource("Qual o IVA?", "23%");

        publicationService.publish(org.getId(), userId, "publisher@taxia.pt", qa.getId());

        var updated = qaRepository.findById(qa.getId()).get();
        assertThat(updated.isPublished()).isTrue();
        assertThat(updated.getPublishedAt()).isNotNull();
        assertThat(updated.getPublishedBy()).isEqualTo("publisher@taxia.pt");
    }

    // 20. publish() duas vezes → CONFLICT
    @Test
    void publish_alreadyPublished_throwsConflict() {
        KnowledgeQuestionAnswer qa = savedValidatedQaWithSource("Pergunta duplicada?", "Resposta.");

        publicationService.publish(org.getId(), userId, "pub@taxia.pt", qa.getId());

        assertThatThrownBy(() -> publicationService.publish(org.getId(), userId, "pub@taxia.pt", qa.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already published");
    }

    // 21. unpublish() → publishedAt limpo
    @Test
    void unpublish_publishedEntry_clearsPublishedAt() {
        KnowledgeQuestionAnswer qa = savedValidatedQaWithSource("Pergunta para despublicar?", "Resposta.");
        publicationService.publish(org.getId(), userId, "pub@taxia.pt", qa.getId());

        publicationService.unpublish(org.getId(), userId, qa.getId());

        var updated = qaRepository.findById(qa.getId()).get();
        assertThat(updated.isPublished()).isFalse();
        assertThat(updated.getPublishedAt()).isNull();
    }

    // 22. publish() sem fonte → não elegível
    @Test
    void publish_withoutSourceReference_throwsValidationError() {
        KnowledgeQuestionAnswer qa = savedValidatedQa("Sem fonte?", "Resposta.");

        assertThatThrownBy(() -> publicationService.publish(org.getId(), userId, "pub@taxia.pt", qa.getId()))
                .isInstanceOf(BusinessException.class);
    }

    // 23. createNewVersion() cria nova entrada com previousVersionId
    @Test
    void createNewVersion_createsEntryWithPreviousVersionId() {
        KnowledgeQuestionAnswer qa = savedValidatedQaWithSource("Versão original?", "Resposta original.");
        publicationService.publish(org.getId(), userId, "pub@taxia.pt", qa.getId());

        KnowledgeQuestionAnswer newVersion = publicationService.createNewVersion(
                org.getId(), userId, "pub@taxia.pt", qa.getId(), "Resposta nova e melhorada.");

        assertThat(newVersion.getPreviousVersionId()).isEqualTo(qa.getId());
        assertThat(newVersion.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.PENDING_REVIEW);
        // ADR-005: old version stays published until publish-replacing
        assertThat(qaRepository.findById(qa.getId()).get().isPublished()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Regra editorial: technicalAnswer obrigatória para publicação/embeddings
    // -------------------------------------------------------------------------

    // (a) shortAnswer sem technicalAnswer → publicação bloqueada
    @Test
    void publish_shortAnswerOnly_isBlocked() {
        KnowledgeQuestionAnswer qa =
                savedShortOnlyValidatedQaWithSource("Só resposta curta?", "Resposta curta validada.");

        assertThatThrownBy(() -> publicationService.publish(org.getId(), userId, "pub@taxia.pt", qa.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("technicalAnswer");

        var unchanged = qaRepository.findById(qa.getId()).get();
        assertThat(unchanged.isPublished()).isFalse();
        assertThat(unchanged.getPublishedAt()).isNull();
    }

    // (b) shortAnswer + technicalAnswer → fluxo normal segue
    @Test
    void publish_withShortAndTechnicalAnswer_succeeds() {
        KnowledgeQuestionAnswer qa = savedValidatedQaWithSource("Com ambas?", "Resposta completa.");
        assertThat(qa.getShortAnswer()).isNotBlank();
        assertThat(qa.getTechnicalAnswer()).isNotBlank();

        publicationService.publish(org.getId(), userId, "pub@taxia.pt", qa.getId());

        assertThat(qaRepository.findById(qa.getId()).get().isPublished()).isTrue();
    }

    // Reindex de publicado legado sem technicalAnswer → bloqueado (sem novo embedding)
    @Test
    void reindex_withoutTechnicalAnswer_isBlocked() {
        KnowledgeQuestionAnswer qa = savedValidatedQaWithSource("Legado?", "Resposta legada.");
        publicationService.publish(org.getId(), userId, "pub@taxia.pt", qa.getId());

        // Simula uma linha legada, publicada antes da regra, sem technicalAnswer. Já não é possível
        // chegar a este estado pela curadoria (ADR-005), por isso é escrita directamente na BD.
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE knowledge_question_answers SET technical_answer = NULL WHERE id = :id")
                .setParameter("id", qa.getId()).executeUpdate();
        entityManager.clear();

        assertThatThrownBy(() -> publicationService.reindex(org.getId(), userId, qa.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("technicalAnswer");
    }

    // (d) IMPORTED → PENDING_REVIEW continua a funcionar sem technicalAnswer
    @Test
    void pendingReview_withoutTechnicalAnswer_stillAllowed() {
        var qa = new KnowledgeQuestionAnswer(org, "Sem técnica?", "Original.", "test", null);
        qa.updateCuration(null, "Só curta.", null, KnowledgeTopic.IVA, null, "PT",
                KnowledgeRiskLevel.LOW, false, null, null, null);
        qa = qaRepository.save(qa);

        qa.markPendingReview();
        qaRepository.save(qa);

        assertThat(qaRepository.findById(qa.getId()).get().getCurationStatus())
                .isEqualTo(KnowledgeCurationStatus.PENDING_REVIEW);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private KnowledgeQuestionAnswer savedValidatedQa(String question, String answer) {
        var qa = new KnowledgeQuestionAnswer(org, question, answer, "test", null);
        // Publicação exige shortAnswer E technicalAnswer (regra editorial TaxIA).
        qa.updateCuration(null, answer, answer + " Fundamentação técnica completa.",
                KnowledgeTopic.IVA, null, "PT",
                KnowledgeRiskLevel.LOW, false, null, null, null);
        qa.markPendingReview();
        qa.validate("revisor");
        return qaRepository.save(qa);
    }

    /** Fonte primeiro, validação depois: acrescentar uma fonte a uma VALIDATED devolve-a a revisão (ADR-005). */
    private KnowledgeQuestionAnswer savedValidatedQaWithSource(String question, String answer) {
        var qa = new KnowledgeQuestionAnswer(org, question, answer, "test", null);
        qa.updateCuration(null, answer, answer + " Fundamentação técnica completa.",
                KnowledgeTopic.IVA, null, "PT", KnowledgeRiskLevel.LOW, false, null, null, null);
        qa.markPendingReview();
        qa = qaRepository.save(qa);
        curationService.addSource(org.getId(), userId, qa.getId(), new SourceReferenceRequest(
                KnowledgeSourceType.LEGISLATION, "CIVA", null, null, null, null, null, null, null));
        qa = qaRepository.findById(qa.getId()).orElseThrow();
        qa.validate("revisor");
        return qaRepository.save(qa);
    }

    /** Caso VALIDATED apenas com shortAnswer — proibido publicar. */
    private KnowledgeQuestionAnswer savedShortOnlyValidatedQaWithSource(String question, String answer) {
        var qa = new KnowledgeQuestionAnswer(org, question, answer, "test", null);
        qa.updateCuration(null, answer, null, KnowledgeTopic.IVA, null, "PT",
                KnowledgeRiskLevel.LOW, false, null, null, null);
        qa.markPendingReview();
        qa = qaRepository.save(qa);
        curationService.addSource(org.getId(), userId, qa.getId(), new SourceReferenceRequest(
                KnowledgeSourceType.LEGISLATION, "CIVA", null, null, null, null, null, null, null));
        qa = qaRepository.findById(qa.getId()).orElseThrow();
        qa.validate("revisor");
        return qaRepository.save(qa);
    }
}
