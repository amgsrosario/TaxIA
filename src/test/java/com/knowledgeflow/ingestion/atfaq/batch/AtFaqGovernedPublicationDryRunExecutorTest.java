package com.knowledgeflow.ingestion.atfaq.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ingestion.atfaq.AtFaqNormalizer;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the governed AT-FAQ publication DRY-RUN executor (Bloco E — E8B.1).
 *
 * <p>Proves that the executor rehearses future publication over an E8A materialization result:
 * it re-validates the guards, produces simulated commands for clean drafts, and keeps zero real
 * effects ({@code persisted == 0}, {@code published == 0}, {@code indexed == 0},
 * {@code wouldIndex == 0}). Pure unit test: no Spring context, no database, no HTTP, no LLM, no
 * publication service, no embedding indexer.
 */
class AtFaqGovernedPublicationDryRunExecutorTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-06T10:15:30Z");

    private AtFaqGovernedPublicationDryRunExecutor executor;
    private AtFaqMaterializationResult materializationResult;

    @BeforeEach
    void setUp() {
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
        executor = new AtFaqGovernedPublicationDryRunExecutor(clock);

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
    }

    // --- report shape from full E4→E8A pipeline -----------------------------

    @Test
    void producesReportFromMaterializationResult() {
        AtFaqPublicationDryRunReport report = executor.dryRun(materializationResult, "test-executor");

        assertThat(report.batchId()).isEqualTo(materializationResult.batchId());
        assertThat(report.executedBy()).isEqualTo("test-executor");
        assertThat(report.executedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(report.mode()).isEqualTo(AtFaqPublicationDryRunMode.DRY_RUN_ONLY);
        assertThat(report.totals().totalDrafts()).isEqualTo(materializationResult.itemResults().size());
    }

    @Test
    void cleanDraftBecomesSimulatedCommand() {
        AtFaqPublicationDryRunReport report = executor.dryRun(materializationResult, "test-executor");

        assertThat(report.itemResults()).hasSize(1);
        AtFaqPublicationDryRunItemResult item = report.itemResults().get(0);
        assertThat(item.externalId()).isEqualTo("AT-FAQ-1001");
        assertThat(item.eligibleForDryRunPublication()).isTrue();
        assertThat(item.simulated()).isTrue();
        assertThat(item.command()).isNotNull();
        assertThat(item.command().intendedAction()).isEqualTo("PUBLISH_GOVERNED_DRY_RUN");
        assertThat(item.command().guardChecks()).contains(
                "at-least-one-official-source", "at-least-one-legal-reference", "technical-answer-present");
    }

    @Test
    void simulatedCommandDeclaresPublicationIntentButNotIndexing() {
        AtFaqPublicationDryRunReport report = executor.dryRun(materializationResult, "test-executor");
        AtFaqPublicationDryRunCommand command = report.itemResults().get(0).command();

        assertThat(command.wouldPersistKnowledgeQa()).isTrue();
        assertThat(command.wouldCreateSources()).isTrue();
        assertThat(command.wouldPublish()).isTrue();
        assertThat(command.wouldIndex()).isFalse();
        assertThat(command.intendedCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
    }

    @Test
    void totalsReflectSimulationAndIntent() {
        AtFaqPublicationDryRunReport report = executor.dryRun(materializationResult, "test-executor");
        var t = report.totals();

        assertThat(t.simulated()).isPositive();
        assertThat(t.eligibleForDryRunPublication()).isEqualTo(t.simulated());
        assertThat(t.wouldPersistKnowledgeQa()).isPositive();
        assertThat(t.wouldCreateSources()).isPositive();
        assertThat(t.wouldPublish()).isPositive();
    }

    // --- zero real effects (the whole point) --------------------------------

    @Test
    void nothingIsPersistedPublishedOrIndexed() {
        AtFaqPublicationDryRunReport report = executor.dryRun(materializationResult, "test-executor");
        var t = report.totals();

        assertThat(t.persisted()).isZero();
        assertThat(t.published()).isZero();
        assertThat(t.indexed()).isZero();
        assertThat(t.wouldIndex()).isZero();
        assertThat(report.itemResults()).allSatisfy(i -> {
            assertThat(i.persisted()).isFalse();
            assertThat(i.published()).isFalse();
            assertThat(i.indexed()).isFalse();
            assertThat(i.knowledgeQaId()).isNull();
        });
    }

    @Test
    void globalNextActionsReinforceNothingPublished() {
        AtFaqPublicationDryRunReport report = executor.dryRun(materializationResult, "test-executor");
        assertThat(report.nextActions())
                .anyMatch(a -> a.contains("persisted=0") && a.contains("published=0") && a.contains("indexed=0"));
    }

    // --- skipped: non-materialized item -------------------------------------

    @Test
    void nonMaterializedItemIsSkippedNotBlocked() {
        AtFaqMaterializationResult withSkip = resultWith(List.of(
                nonMaterializedItem("AT-FAQ-SKIP")));

        AtFaqPublicationDryRunReport report = executor.dryRun(withSkip, "test-executor");

        assertThat(report.totals().skipped()).isEqualTo(1);
        assertThat(report.totals().blocked()).isZero();
        assertThat(report.totals().simulated()).isZero();
        AtFaqPublicationDryRunItemResult item = report.itemResults().get(0);
        assertThat(item.simulated()).isFalse();
        assertThat(item.blockingReasons()).isEmpty();
        assertThat(item.nextActions()).anyMatch(a -> a.toLowerCase().contains("ignorado"));
    }

    // --- blocked: materialized draft missing a required field ---------------

    @Test
    void materializedDraftWithoutLegalReferenceIsBlocked() {
        AtFaqMaterializationCandidate draft = draft(
                "AT-FAQ-NOREF", "Resposta técnica.", List.of(officialSource()), List.of());
        AtFaqMaterializationResult withBlocked = resultWith(List.of(materializedItem(draft)));

        AtFaqPublicationDryRunReport report = executor.dryRun(withBlocked, "test-executor");

        assertThat(report.totals().blocked()).isEqualTo(1);
        assertThat(report.totals().simulated()).isZero();
        AtFaqPublicationDryRunItemResult item = report.itemResults().get(0);
        assertThat(item.eligibleForDryRunPublication()).isFalse();
        assertThat(item.command()).isNull();
        assertThat(item.blockingReasons()).contains("at-least-one-legal-reference");
    }

    @Test
    void materializedDraftWithoutOfficialSourceIsBlocked() {
        AtFaqMaterializationCandidate draft = draft(
                "AT-FAQ-NOOFF", "Resposta técnica.", List.of(nonOfficialSource()), List.of("Artigo 41.º do CIVA"));
        AtFaqMaterializationResult withBlocked = resultWith(List.of(materializedItem(draft)));

        AtFaqPublicationDryRunReport report = executor.dryRun(withBlocked, "test-executor");

        assertThat(report.totals().blocked()).isEqualTo(1);
        assertThat(report.itemResults().get(0).blockingReasons()).contains("at-least-one-official-source");
    }

    // --- blocked: artificial real effect already present on the input -------

    @Test
    void materializedItemWithKnowledgeQaIdIsBlocked() {
        AtFaqMaterializationCandidate draft = cleanDraft("AT-FAQ-HASID");
        AtFaqMaterializationItemResult tampered = new AtFaqMaterializationItemResult(
                "AT-FAQ-HASID", true, false, false, false,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                draft.normalizedQuestion(), draft, List.of(), List.of(), List.of());
        AtFaqPublicationDryRunReport report = executor.dryRun(resultWith(List.of(tampered)), "test-executor");

        assertThat(report.totals().blocked()).isEqualTo(1);
        assertThat(report.itemResults().get(0).blockingReasons()).contains("input-has-no-knowledge-qa-id");
    }

    @Test
    void materializedItemAlreadyPublishedOrIndexedIsBlocked() {
        AtFaqMaterializationCandidate draft = cleanDraft("AT-FAQ-TAINTED");
        AtFaqMaterializationItemResult tampered = new AtFaqMaterializationItemResult(
                "AT-FAQ-TAINTED", true, true /* persisted */, true /* published */, true /* indexed */,
                null, draft.normalizedQuestion(), draft, List.of(), List.of(), List.of());
        AtFaqPublicationDryRunReport report = executor.dryRun(resultWith(List.of(tampered)), "test-executor");

        assertThat(report.totals().blocked()).isEqualTo(1);
        assertThat(report.itemResults().get(0).blockingReasons())
                .contains("input-not-persisted", "input-not-published", "input-not-indexed");
    }

    // --- totals reconcile ----------------------------------------------------

    @Test
    void totalsReconcile() {
        AtFaqPublicationDryRunReport report = executor.dryRun(materializationResult, "test-executor");
        var t = report.totals();

        assertThat(t.eligibleForDryRunPublication() + t.skipped() + t.blocked())
                .isEqualTo(t.totalDrafts());
    }

    // --- determinism / idempotency ------------------------------------------

    @Test
    void executionIsDeterministic() {
        AtFaqPublicationDryRunReport first = executor.dryRun(materializationResult, "test-executor");
        AtFaqPublicationDryRunReport second = executor.dryRun(materializationResult, "test-executor");

        assertThat(second).isEqualTo(first);
        assertThat(second.executedAt()).isEqualTo(first.executedAt());
    }

    // --- robustness ----------------------------------------------------------

    @Test
    void nullMaterializationResultProducesEmptyReport() {
        AtFaqPublicationDryRunReport report = executor.dryRun(null, "test-executor");

        assertThat(report.itemResults()).isEmpty();
        assertThat(report.totals().totalDrafts()).isZero();
        assertThat(report.totals().persisted()).isZero();
        assertThat(report.totals().published()).isZero();
        assertThat(report.totals().indexed()).isZero();
    }

    @Test
    void blankExecutedByFallsBackToSystem() {
        AtFaqPublicationDryRunReport report = executor.dryRun(materializationResult, "   ");
        assertThat(report.executedBy()).isEqualTo("system");
    }

    // --- no leakage of raw HTML / prompts / chunks --------------------------

    @Test
    void outputContainsNoRawHtmlPromptsOrChunks() {
        AtFaqPublicationDryRunReport report = executor.dryRun(materializationResult, "test-executor");

        List<String> texts = new ArrayList<>();
        texts.addAll(report.globalWarnings());
        texts.addAll(report.blockingErrors());
        texts.addAll(report.nextActions());
        for (AtFaqPublicationDryRunItemResult item : report.itemResults()) {
            texts.add(item.normalizedQuestion());
            texts.addAll(item.warnings());
            texts.addAll(item.blockingReasons());
            texts.addAll(item.nextActions());
            AtFaqPublicationDryRunCommand c = item.command();
            if (c != null) {
                texts.add(c.normalizedQuestion());
                texts.add(c.intendedAction());
                texts.addAll(c.guardChecks());
                texts.addAll(c.warnings());
                texts.addAll(c.blockingReasons());
            }
        }
        for (String t : texts) {
            if (t != null) {
                assertThat(t).doesNotContain("<").doesNotContain("```");
                assertThat(t.toLowerCase()).doesNotContain("prompt").doesNotContain("chunk");
            }
        }
    }

    // --- builders ------------------------------------------------------------

    private static AtFaqMaterializationResult resultWith(List<AtFaqMaterializationItemResult> items) {
        int materialized = (int) items.stream().filter(AtFaqMaterializationItemResult::materialized).count();
        AtFaqMaterializationTotals totals = new AtFaqMaterializationTotals(
                items.size(), materialized, materialized, 0, 0, 0, 0, 0, 0, 0);
        return new AtFaqMaterializationResult(
                "AT-BATCH-TEST", FIXED_INSTANT, "test-materializer", totals, items,
                List.of(), List.of(), List.of());
    }

    private static AtFaqMaterializationItemResult materializedItem(AtFaqMaterializationCandidate draft) {
        return new AtFaqMaterializationItemResult(
                draft.externalId(), true, false, false, false, null,
                draft.normalizedQuestion(), draft, List.of(), List.of(), List.of());
    }

    private static AtFaqMaterializationItemResult nonMaterializedItem(String externalId) {
        return new AtFaqMaterializationItemResult(
                externalId, false, false, false, false, null,
                "Qual o prazo?", null, List.of(), List.of("some-guard"), List.of());
    }

    private static AtFaqMaterializationCandidate cleanDraft(String externalId) {
        return draft(externalId, "Resposta técnica.", List.of(officialSource()), List.of("Artigo 41.º do CIVA"));
    }

    private static AtFaqMaterializationCandidate draft(
            String externalId,
            String technicalAnswer,
            List<AtFaqMaterializationSourceCandidate> sources,
            List<String> legalReferences) {
        return new AtFaqMaterializationCandidate(
                externalId,
                "Qual o prazo?",
                "Resposta curta.",
                technicalAnswer,
                KnowledgeTopic.IVA,
                "prazos",
                "PT",
                KnowledgeRiskLevel.LOW,
                KnowledgeCurationStatus.IMPORTED,
                sources,
                legalReferences,
                List.of(),
                List.of());
    }

    private static AtFaqMaterializationSourceCandidate officialSource() {
        return new AtFaqMaterializationSourceCandidate(
                KnowledgeSourceType.OFFICIAL_FAQ, "FAQ AT", "https://info.portaldasfinancas.gov.pt/faq",
                "Artigo 41.º do CIVA", true, true, List.of());
    }

    private static AtFaqMaterializationSourceCandidate nonOfficialSource() {
        return new AtFaqMaterializationSourceCandidate(
                KnowledgeSourceType.OTHER, "Blog fiscal", "https://exemplo.pt/artigo",
                null, false, false, List.of());
    }
}
