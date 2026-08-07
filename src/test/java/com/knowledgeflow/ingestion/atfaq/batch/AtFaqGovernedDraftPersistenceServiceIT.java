package com.knowledgeflow.ingestion.atfaq.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ingestion.atfaq.AtFaqNormalizer;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Governed AT-FAQ draft persistence over real PostgreSQL (Testcontainers) — Bloco E, E8B.2.
 *
 * <p>Runs the full pipeline E4→E5→E6→E7→E8A→E8B.1 in memory with a fixed clock, then persists the
 * dry-run-verified drafts through {@link AtFaqGovernedDraftPersistenceService} into an isolated
 * database. Proves that only the clean item survives as a curable {@code IMPORTED}
 * {@code KnowledgeQuestionAnswer} plus its sources, that it is never published/indexed/RAG-eligible,
 * that no embeddings are created, that persistence is idempotent, and that the future-autonomy
 * classification is correct.
 *
 * <p>Zero external calls, only fictitious fixture data. No publication service and no embedding
 * indexer are wired in — the persistence service references neither.
 *
 * <p>Run: mvn verify -Ppgtest -Dit.test=AtFaqGovernedDraftPersistenceServiceIT
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AtFaqGovernedDraftPersistenceServiceIT {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-06T10:15:30Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired KnowledgeSourceReferenceRepository sourceRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired JdbcTemplate jdbc;

    private Organization org;
    private AtFaqGovernedDraftPersistenceService persistenceService;
    private AtFaqMaterializationResult materializationResult;
    private AtFaqPublicationDryRunReport dryRunReport;

    // Shared across ordered tests.
    private AtFaqDraftPersistenceResult firstRun;

    @BeforeAll
    void setUp() {
        org = organizationRepository.save(new Organization("Org — Persistência E8B.2", null));

        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        AtFaqNormalizer normalizer = new AtFaqNormalizer();
        AtFaqControlledBatchService batchService = new AtFaqControlledBatchService(normalizer, clock);
        AtFaqPreCurationService preCurationService =
                new AtFaqPreCurationService(batchService, normalizer, clock);
        AtFaqReviewService reviewService = new AtFaqReviewService(clock);
        AtFaqGovernedPublicationPlanService planService =
                new AtFaqGovernedPublicationPlanService(clock);
        AtFaqGovernedMaterializationService materializationService =
                new AtFaqGovernedMaterializationService(clock, new AtFaqKnowledgeQaDraftAssembler());
        AtFaqGovernedPublicationDryRunExecutor dryRunExecutor =
                new AtFaqGovernedPublicationDryRunExecutor(clock);
        persistenceService = new AtFaqGovernedDraftPersistenceService(
                qaRepository, sourceRepository, clock);

        AtFaqPreCurationResult preCuration =
                preCurationService.preCurate(ControlledBatchFixtures.sixItemBatch());
        AtFaqReviewResult review = reviewService.review(preCuration, List.of(
                AtFaqReviewDecision.of("AT-FAQ-1001", AtFaqReviewDecisionType.ACCEPT_AUTO_CONTROLLED_CANDIDATE, "test-reviewer", "Base sólida."),
                AtFaqReviewDecision.of("AT-FAQ-1002", AtFaqReviewDecisionType.SEND_TO_ASSISTED_REVIEW, "test-reviewer", "Falta base legal."),
                AtFaqReviewDecision.of("AT-FAQ-1003", AtFaqReviewDecisionType.REJECT, "test-reviewer", "Duplicado."),
                AtFaqReviewDecision.of("AT-FAQ-1004", AtFaqReviewDecisionType.REQUIRE_MANUAL_REVIEW, "test-reviewer", "Conflito."),
                AtFaqReviewDecision.of("AT-FAQ-1005", AtFaqReviewDecisionType.REQUIRE_MANUAL_REVIEW, "test-reviewer", "Risco alto."),
                AtFaqReviewDecision.of("AT-FAQ-1006", AtFaqReviewDecisionType.REJECT, "test-reviewer", "Sem resposta técnica.")),
                "test-reviewer");
        AtFaqGovernedPublicationPlan plan = planService.plan(preCuration, review, "test-planner");
        materializationResult = materializationService.materializeDrafts(plan, "test-materializer");
        dryRunReport = dryRunExecutor.dryRun(materializationResult, "test-executor");
    }

    // =========================================================================
    // TC-01 — persiste apenas o item limpo
    // =========================================================================

    @Test @Order(1)
    @DisplayName("TC-01: persiste apenas o item limpo como KnowledgeQuestionAnswer")
    void persistsOnlyCleanItem() {
        firstRun = persistenceService.persistDrafts(
                materializationResult, dryRunReport, org, "test-persister");

        assertThat(firstRun.mode()).isEqualTo(AtFaqDraftPersistenceMode.DRY_RUN_VERIFIED);
        assertThat(firstRun.persistedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(firstRun.totals().totalDrafts()).isEqualTo(1);
        assertThat(firstRun.totals().persisted()).isEqualTo(1);
        assertThat(firstRun.totals().blocked()).isZero();
        assertThat(firstRun.itemResults()).hasSize(1);
        assertThat(firstRun.itemResults().get(0).externalId()).isEqualTo("AT-FAQ-1001");
        assertThat(countQa()).isEqualTo(1);
    }

    // =========================================================================
    // TC-02 — KnowledgeQuestionAnswer em estado conservador
    // =========================================================================

    @Test @Order(2)
    @DisplayName("TC-02: QA persistido com IMPORTED, publishedAt/publishedBy nulos, campos curados")
    void persistedQaIsConservativeDraft() {
        KnowledgeQuestionAnswer qa = loadCleanQa();

        assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.IMPORTED);
        assertThat(qa.getPublishedAt()).isNull();
        assertThat(qa.getPublishedBy()).isNull();
        assertThat(qa.getOrganization().getId()).isEqualTo(org.getId());
        assertThat(qa.getNormalizedQuestion()).isNotBlank();
        assertThat(qa.getShortAnswer()).isNotBlank();
        assertThat(qa.getTechnicalAnswer()).isNotBlank();
        assertThat(qa.getTopic()).isNotNull();
        assertThat(qa.getRiskLevel()).isNotNull();
        assertThat(qa.getJurisdiction()).isEqualTo("PT");
    }

    // =========================================================================
    // TC-03 — nunca elegível para RAG
    // =========================================================================

    @Test @Order(3)
    @DisplayName("TC-03: QA persistido não é elegível para RAG (IMPORTED, não publicado)")
    void persistedQaIsNotRagEligible() {
        KnowledgeQuestionAnswer qa = loadCleanQa();
        assertThat(qa.isEligibleForRag()).isFalse();
        assertThat(qa.isPublished()).isFalse();
    }

    // =========================================================================
    // TC-04 — fontes associadas persistidas
    // =========================================================================

    @Test @Order(4)
    @DisplayName("TC-04: fontes associadas persistidas, incluindo fonte oficial e referência legal")
    void sourcesPersisted() {
        KnowledgeQuestionAnswer qa = loadCleanQa();
        List<KnowledgeSourceReference> sources =
                sourceRepository.findByQuestionAnswerId(qa.getId());

        assertThat(sources).isNotEmpty();
        assertThat(sources).allSatisfy(s -> assertThat(s.getTitle()).isNotBlank());
        assertThat(sources).anyMatch(s -> s.getLegalReference() != null && !s.getLegalReference().isBlank());
        assertThat(firstRun.itemResults().get(0).sourcesPersisted()).isTrue();
    }

    // =========================================================================
    // TC-05 — nenhum embedding criado
    // =========================================================================

    @Test @Order(5)
    @DisplayName("TC-05: nenhuma linha em knowledge_qa_embeddings; totals.embeddings == 0")
    void noEmbeddingsCreated() {
        assertThat(countEmbeddings()).isZero();
        assertThat(firstRun.totals().embeddings()).isZero();
        assertThat(firstRun.totals().published()).isZero();
        assertThat(firstRun.totals().indexed()).isZero();
    }

    // =========================================================================
    // TC-06 — classificação de autonomia futura
    // =========================================================================

    @Test @Order(6)
    @DisplayName("TC-06: item limpo é eligibleForAutoPublicationFuture e não requer humano")
    void autonomyClassificationForCleanItem() {
        AtFaqDraftPersistenceItemResult item = firstRun.itemResults().get(0);

        assertThat(item.eligibleForAutoPublicationFuture()).isTrue();
        assertThat(item.requiresHumanIntervention()).isFalse();
        assertThat(item.autonomySignals()).contains(
                "AUTO_PUBLICATION_FUTURE_ELIGIBLE",
                "OFFICIAL_SOURCE_PRESENT",
                "LEGAL_REFERENCE_PRESENT",
                "TECHNICAL_ANSWER_PRESENT",
                "LOW_RISK",
                "NO_CONFLICTS",
                "NO_BLOCKING_DUPLICATE");
        assertThat(firstRun.totals().autoPublicationFutureEligible()).isEqualTo(1);
        assertThat(firstRun.totals().humanInterventionLikely()).isZero();
    }

    // =========================================================================
    // TC-07 — idempotência
    // =========================================================================

    @Test @Order(7)
    @DisplayName("TC-07: segunda execução não duplica; reutiliza o draft existente")
    void secondRunIsIdempotent() {
        AtFaqDraftPersistenceResult secondRun = persistenceService.persistDrafts(
                materializationResult, dryRunReport, org, "test-persister");

        assertThat(secondRun.totals().totalDrafts()).isEqualTo(1);
        assertThat(secondRun.totals().persisted()).isZero();
        assertThat(secondRun.totals().skipped()).isEqualTo(1);
        assertThat(secondRun.totals().eligibleForPersistence()).isEqualTo(1);
        assertThat(countQa()).isEqualTo(1);
        // Reused item points at the same persisted Q&A.
        assertThat(secondRun.itemResults().get(0).knowledgeQaId())
                .isEqualTo(loadCleanQa().getId());
    }

    // =========================================================================
    // TC-08 — apenas 1 QA na organização (nada de assisted/manual/blocked/deferred)
    // =========================================================================

    @Test @Order(8)
    @DisplayName("TC-08: apenas o item limpo foi persistido; casos não-limpos ficam fora da BD")
    void onlyCleanItemInDatabase() {
        assertThat(countQa()).isEqualTo(1);
        List<KnowledgeQuestionAnswer> all =
                qaRepository.findByOrganizationId(org.getId(),
                        PageRequest.of(0, 50)).getContent();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getExternalKey()).isEqualTo("AT-FAQ-1001");
    }

    // =========================================================================
    // TC-09 — sem publicação em toda a BD
    // =========================================================================

    @Test @Order(9)
    @DisplayName("TC-09: nenhum QA publicado; publishedAt/publishedBy nulos em toda a organização")
    void nothingPublishedAnywhere() {
        Long published = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_question_answers "
                        + "WHERE organization_id = ?::uuid AND published_at IS NOT NULL",
                Long.class, org.getId().toString());
        assertThat(published).isZero();
    }

    // =========================================================================
    // TC-10 — output sem HTML bruto/prompts/chunks
    // =========================================================================

    @Test @Order(10)
    @DisplayName("TC-10: relatório não contém HTML bruto, prompts nem chunks")
    void outputContainsNoRawHtmlPromptsOrChunks() {
        List<String> texts = new ArrayList<>();
        texts.addAll(firstRun.globalWarnings());
        texts.addAll(firstRun.blockingErrors());
        texts.addAll(firstRun.nextActions());
        for (AtFaqDraftPersistenceItemResult item : firstRun.itemResults()) {
            texts.add(item.normalizedQuestion());
            texts.addAll(item.autonomySignals());
            texts.addAll(item.warnings());
            texts.addAll(item.blockingReasons());
            texts.addAll(item.nextActions());
        }
        for (String t : texts) {
            if (t != null) {
                assertThat(t).doesNotContain("<").doesNotContain("```");
                assertThat(t.toLowerCase()).doesNotContain("prompt").doesNotContain("chunk");
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private KnowledgeQuestionAnswer loadCleanQa() {
        return qaRepository.findByOrganizationIdAndSourceSystemAndExternalKey(
                        org.getId(), "at-faq-governed-batch", "AT-FAQ-1001")
                .orElseThrow();
    }

    private long countQa() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_question_answers WHERE organization_id = ?::uuid",
                Long.class, org.getId().toString());
        return n != null ? n : 0L;
    }

    private long countEmbeddings() {
        Long n = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM knowledge_qa_embeddings kqae
                JOIN knowledge_question_answers kqa ON kqa.id = kqae.knowledge_qa_id
                WHERE kqa.organization_id = ?::uuid
                """,
                Long.class, org.getId().toString());
        return n != null ? n : 0L;
    }
}
