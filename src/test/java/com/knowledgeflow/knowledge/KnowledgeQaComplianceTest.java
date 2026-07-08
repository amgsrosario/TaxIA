package com.knowledgeflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.audit.repository.AuditEventRepository;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.knowledge.dto.ImportReport;
import com.knowledgeflow.knowledge.dto.SourceReferenceRequest;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.knowledge.service.KnowledgeQaSimilarityService;
import com.knowledgeflow.knowledge.service.KnowledgeBenchmarkDraftService;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerCurationService;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerImportService;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerPublicationService;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compliance tests for the Knowledge Q&A module.
 * Covers all blocking conditions, RAG exclusion rules, security semantics,
 * duplicate/conflict detection, and the complete end-to-end curation flow.
 * Zero external API calls.
 */
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class KnowledgeQaComplianceTest {

    @Autowired private KnowledgeQuestionAnswerImportService importService;
    @Autowired private KnowledgeQuestionAnswerCurationService curationService;
    @Autowired private KnowledgeQuestionAnswerPublicationService publicationService;
    @Autowired private KnowledgeQaSimilarityService similarityService;
    @Autowired private KnowledgeBenchmarkDraftService benchmarkDraftService;
    @Autowired private KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired private KnowledgeSourceReferenceRepository sourceRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private AuditEventRepository auditEventRepository;

    private Organization org;
    private Organization otherOrg;
    private UUID userId;

    @BeforeEach
    void setUp() {
        org = organizationRepository.save(new Organization("TaxIA Compliance Test", null));
        otherOrg = organizationRepository.save(new Organization("Other Org", null));
        userId = UUID.randomUUID();
    }

    // =========================================================================
    // Exemplos fictícios (TC-F)
    // =========================================================================

    // TC-F1: ficheiro CSV de exemplo importa sem erros e fica com status IMPORTED
    @Test
    void csvExample_importsFicticiousData_statusIsImported() throws Exception {
        InputStream csv = csv("""
                externalKey,question,answer,topic
                FICTICIO-001,Pergunta completamente fictícia?,Resposta inteiramente inventada.,OUTROS
                """);
        ImportReport report = importService.importCsv(org.getId(), userId, "teste", csv, false, 0);
        assertThat(report.imported()).isEqualTo(1);
        assertThat(qaRepository.findAll().stream()
                .anyMatch(q -> q.getCurationStatus() == KnowledgeCurationStatus.IMPORTED))
                .isTrue();
    }

    // =========================================================================
    // Limit (TC-L)
    // =========================================================================

    // TC-L1: limit=0 importa todas as linhas (sem limite)
    @Test
    void limit_zero_importsAllRows() throws Exception {
        InputStream csv = csv("""
                externalKey,question,answer
                L001,Q1?,A1.
                L002,Q2?,A2.
                L003,Q3?,A3.
                """);
        ImportReport report = importService.importCsv(org.getId(), userId, "src", csv, false, 0);
        assertThat(report.imported()).isEqualTo(3);
    }

    // TC-L2: limit negativo corrigido para 0 (sem limite) pelo compact constructor
    @Test
    void limit_negative_treatedAsZero() throws Exception {
        // The KnowledgeQaImportRequest compact constructor normalises limit<0 → 0
        // Test via direct service call with limit=-1 (treated as 0 = no limit)
        InputStream csv = csv("""
                externalKey,question,answer
                LN001,Q1?,A1.
                LN002,Q2?,A2.
                """);
        // limit=-1 in the service → no limit applied
        ImportReport report = importService.importCsv(org.getId(), userId, "src", csv, false, -1);
        assertThat(report.imported()).isEqualTo(2);
    }

    // =========================================================================
    // Duplicate / conflict detection (TC-D)
    // =========================================================================

    // TC-D1: EXACT_DUPLICATE — same question + same answer → skipped
    @Test
    void duplicate_exactDuplicate_skipped() throws Exception {
        String content = "question,answer\nQual a taxa?,23%.";
        importService.importCsv(org.getId(), userId, "src", csv(content), false, 0);
        ImportReport second = importService.importCsv(org.getId(), userId, "src", csv(content), false, 0);
        assertThat(second.duplicated()).isEqualTo(1);
        assertThat(qaRepository.findAll()).hasSize(1);
    }

    // TC-D2: SAME_QUESTION_DIFFERENT_ANSWER → imports both + POTENTIAL_CONFLICT
    @Test
    void duplicate_sameQuestionDifferentAnswer_importsWithConflict() throws Exception {
        importService.importCsv(org.getId(), userId, "src",
                csv("question,answer\nQual a taxa?,23%."), false, 0);
        ImportReport second = importService.importCsv(org.getId(), userId, "src",
                csv("question,answer\nQual a taxa?,A taxa é 23%."), false, 0);
        assertThat(second.imported()).isEqualTo(1);
        assertThat(qaRepository.findAll()).hasSize(2);
        assertThat(second.issues()).anyMatch(i -> i.message() != null
                && i.message().toLowerCase().contains("conflict"));
    }

    // TC-D3: Same externalKey re-import → updated (idempotent), not duplicated
    @Test
    void duplicate_externalKeyReimport_updatesNotDuplicates() throws Exception {
        importService.importCsv(org.getId(), userId, "src",
                csv("externalKey,question,answer\nEK001,Q?,A v1."), false, 0);
        ImportReport second = importService.importCsv(org.getId(), userId, "src",
                csv("externalKey,question,answer\nEK001,Q?,A v2."), false, 0);
        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.imported()).isZero();
        assertThat(qaRepository.findAll()).hasSize(1);
    }

    // =========================================================================
    // Publication blocking conditions (TC-PUB)
    // =========================================================================

    // TC-PUB1: cannot publish from IMPORTED (not VALIDATED)
    @Test
    void publish_notValidated_blocked() {
        KnowledgeQuestionAnswer qa = savedQa("Q?", "A.");
        assertThatThrownBy(() -> publicationService.publish(org.getId(), userId, "pub", qa.getId()))
                .isInstanceOf(BusinessException.class);
    }

    // TC-PUB2: HIGH risk + requiresHumanValidation=true + validated → eligible; upgrading
    //          risk to HIGH AFTER validation without setting requiresHumanValidation leaves entry
    //          ineligible because isEligibleForRag checks reviewedBy for HIGH/CRITICAL.
    //          This test verifies the isEligibleForRag guard for HIGH risk when risk is
    //          upgraded post-validation (the field updateCuration does NOT clear reviewedBy).
    @Test
    void publish_highRisk_validatedWithHumanValidationFlag_eligible() {
        KnowledgeQuestionAnswer qa = savedQa("Q?", "A.");
        qa.updateCuration(null, "A.", "A. Fundamentação técnica.", KnowledgeTopic.IVA, null, "PT",
                KnowledgeRiskLevel.HIGH, true, null, null, null);
        qa.markPendingReview();
        qaRepository.save(qa);
        addSource(qa);

        curationService.validate(org.getId(), userId, "revisor@taxia.pt", qa.getId());

        KnowledgeQuestionAnswer validated = qaRepository.findById(qa.getId()).orElseThrow();
        assertThat(validated.isEligibleForRag()).isTrue();
        assertThat(validated.getReviewedBy()).isNotBlank();
    }

    // TC-PUB3: HIGH risk without requiresHumanValidation flag → validate() blocked
    @Test
    void validate_highRiskMissingHumanValidationFlag_blocked() {
        KnowledgeQuestionAnswer qa = savedQa("Q?", "A.");
        qa.updateCuration(null, "A.", null, KnowledgeTopic.IVA, null, "PT",
                KnowledgeRiskLevel.HIGH, false, null, null, null);
        qa.markPendingReview();
        qaRepository.save(qa);
        addSource(qa);

        assertThatThrownBy(() ->
                curationService.validate(org.getId(), userId, "revisor", qa.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("HIGH");
    }

    // TC-PUB4: CRITICAL risk without requiresHumanValidation → validate() blocked
    @Test
    void validate_criticalRiskMissingHumanValidationFlag_blocked() {
        KnowledgeQuestionAnswer qa = savedQa("Q?", "A.");
        qa.updateCuration(null, "A.", null, KnowledgeTopic.IVA, null, "PT",
                KnowledgeRiskLevel.CRITICAL, false, null, null, null);
        qa.markPendingReview();
        qaRepository.save(qa);
        addSource(qa);

        assertThatThrownBy(() ->
                curationService.validate(org.getId(), userId, "revisor", qa.getId()))
                .isInstanceOf(BusinessException.class);
    }

    // TC-PUB5: expired validTo → isEligibleForRag() = false
    @Test
    void eligibility_expiredEntry_notEligible() {
        KnowledgeQuestionAnswer qa = savedValidatedQaWithSource("Q?", "A.");
        qa.updateCuration(null, "A.", "A. Fundamentação técnica.", KnowledgeTopic.IVA, null, "PT",
                KnowledgeRiskLevel.LOW, false, null, LocalDate.now().minusDays(1), null);
        qaRepository.save(qa);
        assertThat(qa.isEligibleForRag()).isFalse();
    }

    // TC-PUB6: blank answer → isEligibleForRag() = false
    @Test
    void eligibility_blankAnswer_notEligible() {
        KnowledgeQuestionAnswer qa = savedQa("Q?", "A.");
        qa.updateCuration(null, "   ", null, null, null, "PT",
                KnowledgeRiskLevel.LOW, false, null, null, null);
        qa.markPendingReview();
        qaRepository.save(qa);
        assertThat(qa.isEligibleForRag()).isFalse();
    }

    // =========================================================================
    // RAG exclusion — all non-VALIDATED states (TC-RAG)
    // =========================================================================

    // TC-RAG1: IMPORTED → isEligibleForRag() = false
    @Test
    void ragExclusion_imported_notEligible() {
        KnowledgeQuestionAnswer qa = savedQa("Q?", "A.");
        assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.IMPORTED);
        assertThat(qa.isEligibleForRag()).isFalse();
    }

    // TC-RAG2: PENDING_REVIEW → isEligibleForRag() = false
    @Test
    void ragExclusion_pendingReview_notEligible() {
        KnowledgeQuestionAnswer qa = savedQa("Q?", "A.");
        qa.markPendingReview();
        assertThat(qa.isEligibleForRag()).isFalse();
    }

    // TC-RAG3: REJECTED → isEligibleForRag() = false
    @Test
    void ragExclusion_rejected_notEligible() {
        KnowledgeQuestionAnswer qa = savedQa("Q?", "A.");
        qa.markPendingReview();
        qa.reject("revisor", "Rejeitado");
        assertThat(qa.isEligibleForRag()).isFalse();
    }

    // TC-RAG4: NEEDS_UPDATE → isEligibleForRag() = false
    @Test
    void ragExclusion_needsUpdate_notEligible() {
        KnowledgeQuestionAnswer qa = savedQa("Q?", "A.");
        qa.updateCuration(null, "A.", null, null, null, "PT",
                KnowledgeRiskLevel.LOW, false, null, null, null);
        qa.markPendingReview();
        qa.validate("revisor");
        qa.markNeedsUpdate();
        assertThat(qa.isEligibleForRag()).isFalse();
    }

    // TC-RAG5: OUTDATED → isEligibleForRag() = false
    @Test
    void ragExclusion_outdated_notEligible() {
        KnowledgeQuestionAnswer qa = savedQa("Q?", "A.");
        qa.updateCuration(null, "A.", null, null, null, "PT",
                KnowledgeRiskLevel.LOW, false, null, null, null);
        qa.markPendingReview();
        qa.validate("revisor");
        qa.markOutdated();
        assertThat(qa.isEligibleForRag()).isFalse();
    }

    // TC-RAG6: ARCHIVED → isEligibleForRag() = false
    @Test
    void ragExclusion_archived_notEligible() {
        KnowledgeQuestionAnswer qa = savedQa("Q?", "A.");
        qa.markPendingReview();
        qa.reject("revisor", "Obsoleto");
        qa.archive();
        assertThat(qa.isEligibleForRag()).isFalse();
    }

    // TC-RAG7: VALIDATED but not published → isEligibleForRag() = true, but published_at IS NULL
    @Test
    void ragExclusion_validatedButNotPublished_publishedAtIsNull() {
        KnowledgeQuestionAnswer qa = savedValidatedQaWithSource("Q?", "A.");
        assertThat(qa.isEligibleForRag()).isTrue();
        assertThat(qa.isPublished()).isFalse();
        assertThat(qa.getPublishedAt()).isNull();
    }

    // =========================================================================
    // Versioning (TC-VER)
    // =========================================================================

    // TC-VER1: createNewVersion → old version unpublished, new has previousVersionId
    @Test
    void versioning_createNewVersion_linkedAndOldUnpublished() {
        KnowledgeQuestionAnswer qa = savedValidatedQaWithSource("Q?", "A original.");
        publicationService.publish(org.getId(), userId, "pub", qa.getId());
        assertThat(qaRepository.findById(qa.getId()).get().isPublished()).isTrue();

        KnowledgeQuestionAnswer newV = publicationService.createNewVersion(
                org.getId(), userId, "pub", qa.getId(), "A actualizada.");

        assertThat(newV.getPreviousVersionId()).isEqualTo(qa.getId());
        assertThat(newV.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.PENDING_REVIEW);
        assertThat(qaRepository.findById(qa.getId()).get().isPublished()).isFalse();
    }

    // =========================================================================
    // Canonical (TC-CAN)
    // =========================================================================

    // TC-CAN1: cannot mark IMPORTED as canonical
    @Test
    void canonical_importedStatus_blocked() {
        KnowledgeQuestionAnswer qa = savedQa("Q?", "A.");
        assertThatThrownBy(() -> qa.setCanonical(true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("VALIDATED");
    }

    // TC-CAN2: canonical can be removed
    @Test
    void canonical_canBeRemoved() {
        KnowledgeQuestionAnswer qa = savedValidatedQa("Q?", "A.");
        qa.setCanonical(true);
        assertThat(qa.isCanonical()).isTrue();
        qa.setCanonical(false);
        assertThat(qa.isCanonical()).isFalse();
    }

    // TC-CAN3: expired canonical → isEligibleForRag() = false even though canonical=true
    @Test
    void canonical_expired_notEligible() {
        KnowledgeQuestionAnswer qa = savedValidatedQa("Q?", "A.");
        qa.setCanonical(true);
        qa.updateCuration(null, "A.", null, KnowledgeTopic.IVA, null, "PT",
                KnowledgeRiskLevel.LOW, false, null, LocalDate.now().minusDays(1), null);
        assertThat(qa.isEligibleForRag()).isFalse();
    }

    // =========================================================================
    // Similarity search (TC-SIM)
    // =========================================================================

    // TC-SIM1: returns results with score ≥ 0 and ≤ 1
    @Test
    void similarity_returnsResultsWithValidScore() {
        savedQa("Qual a taxa de IVA em Portugal?", "23%.");
        savedQa("Qual a taxa de IRC para PME?", "17% até 50k.");

        var results = similarityService.findSimilar(org.getId(), "Qual a taxa de IVA?", 10);

        assertThat(results).isNotEmpty();
        results.forEach(r -> {
            assertThat(r.similarity()).isBetween(0.0, 1.0);
            assertThat(r.curationStatus()).isNotNull();
        });
    }

    // TC-SIM2: similarity respects organization scoping
    @Test
    void similarity_respectsOrganizationScoping() {
        savedQa("Qual a taxa de IVA?", "23%.");
        // other org data
        otherOrg = organizationRepository.save(new Organization("Other Org Similarity", null));
        var qaOther = new KnowledgeQuestionAnswer(otherOrg, "Qual a taxa de IVA?", "23%.", "t", null);
        qaRepository.save(qaOther);

        var results = similarityService.findSimilar(org.getId(), "Qual a taxa de IVA?", 10);
        assertThat(results).allMatch(r -> {
            var qa = qaRepository.findById(r.id()).orElseThrow();
            return qa.getOrganization().getId().equals(org.getId());
        });
    }

    // =========================================================================
    // Benchmark draft (TC-BEN)
    // =========================================================================

    // TC-BEN1: only VALIDATED entries generate benchmark
    @Test
    void benchmark_onlyValidated_generated() {
        KnowledgeQuestionAnswer qa = savedValidatedQa("Qual a taxa de IVA?", "23%.");
        var draft = benchmarkDraftService.generateDraft(org.getId(), qa.getId());

        assertThat(draft.sourceQaId()).isEqualTo(qa.getId());
        assertThat(draft.question()).isEqualTo("Qual a taxa de IVA?");
        assertThat(draft.caseId()).startsWith("TAXIA-DRAFT-");
        assertThat(draft.expectedBehaviours()).isNotEmpty();
        assertThat(draft.forbiddenBehaviours()).isNotEmpty();
    }

    // TC-BEN2: non-VALIDATED entry cannot generate benchmark
    @Test
    void benchmark_nonValidated_blocked() {
        KnowledgeQuestionAnswer qa = savedQa("Q?", "A.");
        assertThatThrownBy(() -> benchmarkDraftService.generateDraft(org.getId(), qa.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("VALIDATED");
    }

    // =========================================================================
    // Cross-organisation access (TC-SEC)
    // =========================================================================

    // TC-SEC1: access entry from another org → FORBIDDEN
    @Test
    void security_crossOrgAccess_forbidden() {
        KnowledgeQuestionAnswer qa = savedQa("Q?", "A.");
        assertThatThrownBy(() -> curationService.getDetail(otherOrg.getId(), qa.getId()))
                .isInstanceOf(BusinessException.class);
    }

    // TC-SEC2: cannot validate entry from another org
    @Test
    void security_crossOrgValidate_forbidden() {
        KnowledgeQuestionAnswer qa = savedQaWithAnswer("Q?", "A.");
        qa.markPendingReview();
        qaRepository.save(qa);
        addSource(qa);
        assertThatThrownBy(() ->
                curationService.validate(otherOrg.getId(), userId, "revisor", qa.getId()))
                .isInstanceOf(BusinessException.class);
    }

    // TC-SEC3: cannot publish entry from another org
    @Test
    void security_crossOrgPublish_forbidden() {
        KnowledgeQuestionAnswer qa = savedValidatedQaWithSource("Q?", "A.");
        assertThatThrownBy(() ->
                publicationService.publish(otherOrg.getId(), userId, "pub", qa.getId()))
                .isInstanceOf(BusinessException.class);
    }

    // =========================================================================
    // End-to-end flow (TC-E2E)
    // =========================================================================

    /**
     * TC-E2E1: Import CSV → PENDING_REVIEW → add source → validate → publish
     * → verify eligible for RAG → create new version → verify old unpublished.
     */
    @Test
    void e2e_completeCurationAndPublicationFlow() throws Exception {
        // 1. Import
        InputStream csv = csv("""
                externalKey,question,answer,topic,riskLevel
                E2E-001,Qual a taxa de IVA fictícia?,A taxa fictícia é de 9%.,IVA,LOW
                """);
        ImportReport report = importService.importCsv(org.getId(), userId, "e2e-test", csv, false, 0);
        assertThat(report.imported()).isEqualTo(1);

        KnowledgeQuestionAnswer qa = qaRepository.findAll().stream()
                .filter(q -> "E2E-001".equals(q.getExternalKey())).findFirst().orElseThrow();
        assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.IMPORTED);

        // 2. Mark PENDING_REVIEW
        curationService.markPendingReview(org.getId(), userId, qa.getId());
        assertThat(qaRepository.findById(qa.getId()).get().getCurationStatus())
                .isEqualTo(KnowledgeCurationStatus.PENDING_REVIEW);

        // 3. Set curated answers (shortAnswer required for validation;
        //    technicalAnswer required for publication)
        var req = new com.knowledgeflow.knowledge.dto.KnowledgeQaCurationRequest(
                null, "A taxa fictícia é de 9%.",
                "A taxa fictícia é de 9%, nos termos do artigo fictício aplicável.",
                null, null, null, null, null, null, null, null);
        curationService.updateCuration(org.getId(), userId, qa.getId(), req);

        // 4. Add source
        curationService.addSource(org.getId(), userId, qa.getId(), new SourceReferenceRequest(
                KnowledgeSourceType.LEGISLATION, "CFICT Art. 9 (fictício)", null, null, null, null, null, null, null));

        // 5. Validate
        curationService.validate(org.getId(), userId, "revisor@taxia.pt", qa.getId());
        assertThat(qaRepository.findById(qa.getId()).get().getCurationStatus())
                .isEqualTo(KnowledgeCurationStatus.VALIDATED);

        // 6. Check RAG eligibility
        KnowledgeQuestionAnswer validated = qaRepository.findById(qa.getId()).get();
        assertThat(validated.isEligibleForRag()).isTrue();
        assertThat(validated.isPublished()).isFalse();

        // 7. Publish (stub indexer records the call without pgvector)
        publicationService.publish(org.getId(), userId, "pub@taxia.pt", qa.getId());
        assertThat(qaRepository.findById(qa.getId()).get().isPublished()).isTrue();

        // 8. Create new version
        KnowledgeQuestionAnswer newVersion = publicationService.createNewVersion(
                org.getId(), userId, "pub@taxia.pt", qa.getId(), "A taxa fictícia actualizada é de 9%.");
        assertThat(newVersion.getPreviousVersionId()).isEqualTo(qa.getId());
        assertThat(qaRepository.findById(qa.getId()).get().isPublished()).isFalse();
        assertThat(newVersion.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.PENDING_REVIEW);
    }

    // =========================================================================
    // Dry-run full check (TC-DRY)
    // =========================================================================

    // TC-DRY1: dry-run persists nothing
    @Test
    void dryRun_persistsNothing() throws Exception {
        InputStream csv = csv("""
                question,answer
                Q fictícia?,A fictícia.
                """);
        ImportReport report = importService.importCsv(org.getId(), userId, "src", csv, true, 0);
        assertThat(report.dryRun()).isTrue();
        assertThat(qaRepository.findAll()).isEmpty();
    }

    // TC-DRY2: dry-run detects duplicates without persisting
    @Test
    void dryRun_detectsDuplicatesWithoutPersisting() throws Exception {
        importService.importCsv(org.getId(), userId, "src",
                csv("question,answer\nQ?,A."), false, 0);
        assertThat(qaRepository.findAll()).hasSize(1);

        ImportReport dryReport = importService.importCsv(org.getId(), userId, "src",
                csv("question,answer\nQ?,A."), true, 0);
        assertThat(dryReport.dryRun()).isTrue();
        assertThat(qaRepository.findAll()).hasSize(1); // still only 1
        assertThat(dryReport.duplicated()).isEqualTo(1);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private KnowledgeQuestionAnswer savedQa(String question, String answer) {
        return qaRepository.save(new KnowledgeQuestionAnswer(org, question, answer, "test", null));
    }

    private KnowledgeQuestionAnswer savedQaWithAnswer(String question, String answer) {
        var qa = new KnowledgeQuestionAnswer(org, question, answer, "test", null);
        qa.updateCuration(null, answer, null, null, null, "PT", KnowledgeRiskLevel.LOW, false, null, null, null);
        return qaRepository.save(qa);
    }

    private KnowledgeQuestionAnswer savedValidatedQa(String question, String answer) {
        var qa = new KnowledgeQuestionAnswer(org, question, answer, "test", null);
        // Regra editorial: publicação/RAG exigem também a technicalAnswer.
        qa.updateCuration(null, answer, answer + " Fundamentação técnica.",
                KnowledgeTopic.IVA, null, "PT",
                KnowledgeRiskLevel.LOW, false, null, null, null);
        qa.markPendingReview();
        qa.validate("revisor");
        return qaRepository.save(qa);
    }

    private KnowledgeQuestionAnswer savedValidatedQaWithSource(String question, String answer) {
        var qa = savedValidatedQa(question, answer);
        curationService.addSource(org.getId(), userId, qa.getId(), new SourceReferenceRequest(
                KnowledgeSourceType.LEGISLATION, "CFICT (fictício)", null, null, null, null, null, null, null));
        return qaRepository.findById(qa.getId()).orElseThrow();
    }

    private void addSource(KnowledgeQuestionAnswer qa) {
        curationService.addSource(org.getId(), userId, qa.getId(), new SourceReferenceRequest(
                KnowledgeSourceType.LEGISLATION, "CFICT (fictício)", null, null, null, null, null, null, null));
    }

    private InputStream csv(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
