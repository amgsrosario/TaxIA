package com.knowledgeflow.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.audit.enums.AuditAction;
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
    // Remoção de fontes + validação de URL
    // -------------------------------------------------------------------------

    // 19. remover fonte de caso IMPORTED funciona
    @Test
    void removeSource_onImportedCase_removesIt() {
        KnowledgeQuestionAnswer qa = savedQa("Caso importado?", "Resposta.");
        addSource(qa);
        UUID sourceId = sourceRepository.findByQuestionAnswerId(qa.getId()).get(0).getId();

        curationService.removeSource(org.getId(), userId, qa.getId(), sourceId);

        assertThat(sourceRepository.countByQuestionAnswerId(qa.getId())).isZero();
    }

    // 20. remover fonte que não pertence ao caso → NOT_FOUND
    @Test
    void removeSource_belongingToAnotherCase_throwsNotFound() {
        KnowledgeQuestionAnswer qaA = savedQa("Caso A?", "Resposta A.");
        KnowledgeQuestionAnswer qaB = savedQa("Caso B?", "Resposta B.");
        addSource(qaB);
        UUID sourceOfB = sourceRepository.findByQuestionAnswerId(qaB.getId()).get(0).getId();

        assertThatThrownBy(() ->
                curationService.removeSource(org.getId(), userId, qaA.getId(), sourceOfB))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");

        // A fonte do caso B permanece intacta.
        assertThat(sourceRepository.countByQuestionAnswerId(qaB.getId())).isEqualTo(1);
    }

    // 21. remover fonte inexistente → NOT_FOUND
    @Test
    void removeSource_nonExistentSource_throwsNotFound() {
        KnowledgeQuestionAnswer qa = savedQa("Caso?", "Resposta.");

        assertThatThrownBy(() ->
                curationService.removeSource(org.getId(), userId, qa.getId(), UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }

    // 22. remover a ÚLTIMA fonte de caso VALIDATED → bloqueado
    @Test
    void removeSource_lastSourceOfValidatedCase_isBlocked() {
        KnowledgeQuestionAnswer qa = savedQaWithAnswer("Caso a validar?", "Resposta.");
        qa.markPendingReview();
        qaRepository.save(qa);
        addSource(qa);
        curationService.validate(org.getId(), userId, "revisor", qa.getId());
        UUID sourceId = sourceRepository.findByQuestionAnswerId(qa.getId()).get(0).getId();

        assertThatThrownBy(() ->
                curationService.removeSource(org.getId(), userId, qa.getId(), sourceId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("last source");

        assertThat(sourceRepository.countByQuestionAnswerId(qa.getId())).isEqualTo(1);
    }

    // 22b. num caso VALIDATED com 2 fontes, remover uma é permitido (não fica sem fontes)
    @Test
    void removeSource_nonLastSourceOfValidatedCase_isAllowed() {
        KnowledgeQuestionAnswer qa = savedQaWithAnswer("Caso com duas fontes?", "Resposta.");
        qa.markPendingReview();
        qaRepository.save(qa);
        addSource(qa);
        curationService.addSource(org.getId(), userId, qa.getId(), new SourceReferenceRequest(
                KnowledgeSourceType.OFFICIAL_FAQ, "FAQ duplicada", null,
                "https://exemplo.local/faq", null, null, null, null, null));
        curationService.validate(org.getId(), userId, "revisor", qa.getId());
        UUID sourceId = sourceRepository.findByQuestionAnswerId(qa.getId()).get(0).getId();

        curationService.removeSource(org.getId(), userId, qa.getId(), sourceId);

        assertThat(sourceRepository.countByQuestionAnswerId(qa.getId())).isEqualTo(1);
    }

    // 23. remoção fica auditada
    @Test
    void removeSource_isAudited() {
        KnowledgeQuestionAnswer qa = savedQa("Caso auditado?", "Resposta.");
        addSource(qa);
        UUID sourceId = sourceRepository.findByQuestionAnswerId(qa.getId()).get(0).getId();

        curationService.removeSource(org.getId(), userId, qa.getId(), sourceId);

        assertThat(auditEventRepository.findAll())
                .anyMatch(e -> e.getAction() == AuditAction.KNOWLEDGE_QA_SOURCE_REMOVED
                        && e.getEntityId().equals(qa.getId()));
    }

    // 24. criar fonte com URL sem esquema → rejeitado
    @Test
    void addSource_withUrlWithoutScheme_throwsValidationError() {
        KnowledgeQuestionAnswer qa = savedQa("Caso URL inválida?", "Resposta.");

        assertThatThrownBy(() -> curationService.addSource(org.getId(), userId, qa.getId(),
                new SourceReferenceRequest(KnowledgeSourceType.OFFICIAL_FAQ, "FAQ", null,
                        "info.portaldasfinancas.gov.pt/faq", null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("http://");

        assertThat(sourceRepository.countByQuestionAnswerId(qa.getId())).isZero();
    }

    // 24b. esquema não suportado → rejeitado
    @Test
    void addSource_withUnsupportedScheme_throwsValidationError() {
        KnowledgeQuestionAnswer qa = savedQa("Caso esquema errado?", "Resposta.");

        assertThatThrownBy(() -> curationService.addSource(org.getId(), userId, qa.getId(),
                new SourceReferenceRequest(KnowledgeSourceType.OFFICIAL_FAQ, "FAQ", null,
                        "ftp://exemplo.local/doc.pdf", null, null, null, null, null)))
                .isInstanceOf(BusinessException.class);
    }

    // 25. fonte sem URL continua permitida (URL é opcional)
    @Test
    void addSource_withoutUrl_isAllowed() {
        KnowledgeQuestionAnswer qa = savedQa("Caso sem URL?", "Resposta.");

        curationService.addSource(org.getId(), userId, qa.getId(), new SourceReferenceRequest(
                KnowledgeSourceType.INTERNAL_OPINION, "Parecer interno", "CIVA art. 19.º",
                null, null, null, null, null, null));

        assertThat(sourceRepository.countByQuestionAnswerId(qa.getId())).isEqualTo(1);
    }

    // 26. http:// e https:// são aceites
    @Test
    void addSource_withHttpAndHttpsUrls_isAllowed() {
        KnowledgeQuestionAnswer qa = savedQa("Caso URLs válidas?", "Resposta.");

        curationService.addSource(org.getId(), userId, qa.getId(), new SourceReferenceRequest(
                KnowledgeSourceType.OFFICIAL_FAQ, "FAQ https", null,
                "https://info.portaldasfinancas.gov.pt/faq", null, null, null, null, null));
        curationService.addSource(org.getId(), userId, qa.getId(), new SourceReferenceRequest(
                KnowledgeSourceType.LEGISLATION, "Legislação http", null,
                "http://exemplo.local/lei", null, null, null, null, null));

        assertThat(sourceRepository.countByQuestionAnswerId(qa.getId())).isEqualTo(2);
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
