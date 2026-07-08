package com.knowledgeflow.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.audit.repository.AuditEventRepository;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.knowledge.dto.SourceReferenceRequest;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import java.time.LocalDate;
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
class KnowledgeQuestionAnswerCurationServiceTest {

    @Autowired private KnowledgeQuestionAnswerCurationService curationService;
    @Autowired private KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired private KnowledgeSourceReferenceRepository sourceRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private AuditEventRepository auditEventRepository;

    private Organization org;
    private UUID userId;

    @BeforeEach
    void setUp() {
        org = organizationRepository.save(new Organization("TaxIA Curation Test", null));
        userId = UUID.randomUUID();
    }

    // 11. Transição de estado: IMPORTED → PENDING_REVIEW → VALIDATED
    @Test
    void stateTransition_importedToPendingReviewToValidated() {
        KnowledgeQuestionAnswer qa = savedQaWithAnswer("Qual o IVA?", "23%");

        curationService.markPendingReview(org.getId(), userId, qa.getId());
        assertThat(qaRepository.findById(qa.getId()).get().getCurationStatus())
                .isEqualTo(KnowledgeCurationStatus.PENDING_REVIEW);

        addSource(qa);
        curationService.validate(org.getId(), userId, "revisor@taxia.pt", qa.getId());
        assertThat(qaRepository.findById(qa.getId()).get().getCurationStatus())
                .isEqualTo(KnowledgeCurationStatus.VALIDATED);
    }

    // 12. validate() sem fonte → rejeitado
    @Test
    void validate_withoutSourceReference_throwsBusinessException() {
        KnowledgeQuestionAnswer qa = savedQa("Pergunta sem fonte?", "Resposta.");
        curationService.markPendingReview(org.getId(), userId, qa.getId());

        assertThatThrownBy(() -> curationService.validate(org.getId(), userId, "revisor", qa.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("source");
    }

    // 13. validate() com risco HIGH sem requiresHumanValidation → rejeitado
    @Test
    void validate_highRiskWithoutHumanValidationFlag_throwsBusinessException() {
        KnowledgeQuestionAnswer qa = savedHighRiskQa("Questão de risco elevado?", "Resposta.");
        curationService.markPendingReview(org.getId(), userId, qa.getId());
        addSource(qa);

        assertThatThrownBy(() -> curationService.validate(org.getId(), userId, "revisor", qa.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("HIGH");
    }

    // 13b. validate() só com technicalAnswer (sem shortAnswer) → rejeitado
    //      (regra editorial: a shortAnswer curada é obrigatória para validar)
    @Test
    void validate_technicalAnswerOnly_withoutShortAnswer_throwsBusinessException() {
        KnowledgeQuestionAnswer qa = savedQa("Só técnica?", "Resposta original.");
        qa.updateCuration(null, null, "Fundamentação técnica sem resposta curta.",
                null, null, "PT", KnowledgeRiskLevel.LOW, false, null, null, null);
        qa.markPendingReview();
        qaRepository.save(qa);
        addSource(qa);

        assertThatThrownBy(() -> curationService.validate(org.getId(), userId, "revisor", qa.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("shortAnswer");
    }

    // 14. setCanonical() quando já existe canónico no mesmo topic → conflito
    @Test
    void setCanonical_whenAnotherCanonicalExists_throwsConflict() {
        KnowledgeQuestionAnswer qa1 = savedValidatedQa("Pergunta 1?", "Resposta 1.");
        KnowledgeQuestionAnswer qa2 = savedValidatedQa("Pergunta 2?", "Resposta 2.");

        curationService.setCanonical(org.getId(), userId, qa1.getId(), true);

        assertThatThrownBy(() -> curationService.setCanonical(org.getId(), userId, qa2.getId(), true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("canonical");
    }

    // 15. validTo expirado → isEligibleForRag retorna false
    @Test
    void eligibility_expiredValidTo_notEligibleForRag() {
        KnowledgeQuestionAnswer qa = savedQa("Questão expirada?", "Resposta expirada.");
        qa.updateCuration(null, "Resposta expirada.", "Resposta expirada. Fundamentação técnica.",
                null, null, "PT", KnowledgeRiskLevel.LOW, false,
                null, LocalDate.now().minusDays(1), null);
        qa.markPendingReview();
        qaRepository.save(qa);
        addSource(qa);
        qa.validate("revisor");
        qaRepository.save(qa);

        assertThat(qa.isEligibleForRag()).isFalse();
    }

    // 16. reject() muda status para REJECTED
    @Test
    void reject_changesStatusToRejected() {
        KnowledgeQuestionAnswer qa = savedQa("Pergunta a rejeitar?", "Resposta.");
        curationService.markPendingReview(org.getId(), userId, qa.getId());

        curationService.reject(org.getId(), userId, "revisor", qa.getId(), "Resposta incorrecta");

        assertThat(qaRepository.findById(qa.getId()).get().getCurationStatus())
                .isEqualTo(KnowledgeCurationStatus.REJECTED);
    }

    // 17. archive() muda status para ARCHIVED (via REJECTED)
    @Test
    void archive_changesStatusToArchived() {
        KnowledgeQuestionAnswer qa = savedQa("Pergunta para arquivar?", "Resposta.");
        curationService.markPendingReview(org.getId(), userId, qa.getId());
        curationService.reject(org.getId(), userId, "revisor", qa.getId(), "Obsoleta");

        curationService.archive(org.getId(), userId, qa.getId());

        assertThat(qaRepository.findById(qa.getId()).get().getCurationStatus())
                .isEqualTo(KnowledgeCurationStatus.ARCHIVED);
    }

    // 18. addSource() persiste referência
    @Test
    void addSource_persistsSourceReference() {
        KnowledgeQuestionAnswer qa = savedQa("Pergunta com fonte?", "Resposta.");

        curationService.addSource(org.getId(), userId, qa.getId(), new SourceReferenceRequest(
                KnowledgeSourceType.LEGISLATION, "CIVA Art. 18.º", "Art. 18.º CIVA", null, null, null, null, null, null));

        assertThat(sourceRepository.countByQuestionAnswerId(qa.getId())).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private KnowledgeQuestionAnswer savedQa(String question, String answer) {
        var qa = new KnowledgeQuestionAnswer(org, question, answer, "test", null);
        return qaRepository.save(qa);
    }

    private KnowledgeQuestionAnswer savedQaWithAnswer(String question, String answer) {
        var qa = new KnowledgeQuestionAnswer(org, question, answer, "test", null);
        qa.updateCuration(null, answer, null, null, null, "PT", KnowledgeRiskLevel.LOW, false, null, null, null);
        return qaRepository.save(qa);
    }

    private KnowledgeQuestionAnswer savedHighRiskQa(String question, String answer) {
        var qa = new KnowledgeQuestionAnswer(org, question, answer, "test", null);
        qa.updateCuration(null, answer, null, KnowledgeTopic.IVA, null, "PT",
                KnowledgeRiskLevel.HIGH, false, null, null, null);
        return qaRepository.save(qa);
    }

    private KnowledgeQuestionAnswer savedValidatedQa(String question, String answer) {
        var qa = new KnowledgeQuestionAnswer(org, question, answer, "test", null);
        qa.updateCuration(null, answer, null, KnowledgeTopic.IVA, null, "PT",
                KnowledgeRiskLevel.LOW, false, null, null, null);
        qa.markPendingReview();
        qa.validate("revisor");
        return qaRepository.save(qa);
    }

    private void addSource(KnowledgeQuestionAnswer qa) {
        curationService.addSource(org.getId(), userId, qa.getId(), new SourceReferenceRequest(
                KnowledgeSourceType.LEGISLATION, "CIVA", null, null, null, null, null, null, null));
    }
}
