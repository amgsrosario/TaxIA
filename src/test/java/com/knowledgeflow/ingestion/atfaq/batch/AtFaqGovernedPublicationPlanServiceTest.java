package com.knowledgeflow.ingestion.atfaq.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ingestion.atfaq.AtFaqNormalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the governed AT-FAQ publication plan (Bloco E — E7).
 *
 * <p>Proves that the plan cross-references E5 + E6, re-applies the future-publication guards
 * deterministically, and never publishes/indexes/persists. Pure unit test: no Spring context,
 * no database, no HTTP, no LLM, no publication service, no embedding indexer.
 */
class AtFaqGovernedPublicationPlanServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-06T10:15:30Z");

    private AtFaqPreCurationService preCurationService;
    private AtFaqReviewService reviewService;
    private AtFaqGovernedPublicationPlanService planService;
    private AtFaqPreCurationResult preCuration;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        AtFaqNormalizer normalizer = new AtFaqNormalizer();
        AtFaqControlledBatchService batchService = new AtFaqControlledBatchService(normalizer, clock);
        preCurationService = new AtFaqPreCurationService(batchService, normalizer, clock);
        reviewService = new AtFaqReviewService(clock);
        planService = new AtFaqGovernedPublicationPlanService(clock);
        preCuration = preCurationService.preCurate(ControlledBatchFixtures.sixItemBatch());
    }

    /** The "correct" gate decision per fixture item. */
    private AtFaqReviewResult standardReview() {
        return reviewService.review(preCuration, List.of(
                AtFaqReviewDecision.of("AT-FAQ-1001", AtFaqReviewDecisionType.ACCEPT_AUTO_CONTROLLED_CANDIDATE, "test-reviewer", "Base sólida."),
                AtFaqReviewDecision.of("AT-FAQ-1002", AtFaqReviewDecisionType.SEND_TO_ASSISTED_REVIEW, "test-reviewer", "Falta base legal."),
                AtFaqReviewDecision.of("AT-FAQ-1003", AtFaqReviewDecisionType.REJECT, "test-reviewer", "Duplicado."),
                AtFaqReviewDecision.of("AT-FAQ-1004", AtFaqReviewDecisionType.REQUIRE_MANUAL_REVIEW, "test-reviewer", "Conflito."),
                AtFaqReviewDecision.of("AT-FAQ-1005", AtFaqReviewDecisionType.REQUIRE_MANUAL_REVIEW, "test-reviewer", "Risco alto."),
                AtFaqReviewDecision.of("AT-FAQ-1006", AtFaqReviewDecisionType.REJECT, "test-reviewer", "Sem resposta técnica.")),
                "test-reviewer");
    }

    private static AtFaqGovernedPublicationCandidate candidateOf(AtFaqGovernedPublicationPlan plan, String externalId) {
        return plan.candidates().stream()
                .filter(c -> externalId.equals(c.externalId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No plan candidate for " + externalId));
    }

    @Test
    void producesPlanFromPreCurationAndReview() {
        AtFaqGovernedPublicationPlan plan = planService.plan(preCuration, standardReview(), "test-planner");

        assertThat(plan.batchId()).isEqualTo(preCuration.batchId());
        assertThat(plan.plannedBy()).isEqualTo("test-planner");
        assertThat(plan.plannedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(plan.candidates()).hasSize(6);
        assertThat(plan.totals().totalItems()).isEqualTo(6);
        assertThat(plan.nextActions()).isNotEmpty();
    }

    @Test
    void cleanAcceptedItemIsReadyForFuturePublication() {
        AtFaqGovernedPublicationPlan plan = planService.plan(preCuration, standardReview(), "test-planner");
        AtFaqGovernedPublicationCandidate clean = candidateOf(plan, "AT-FAQ-1001");

        assertThat(clean.readiness()).isEqualTo(AtFaqGovernedPublicationReadiness.READY_FOR_FUTURE_PUBLICATION);
        assertThat(clean.readyForFuturePublication()).isTrue();
        assertThat(clean.guardResult().passed()).isTrue();
        assertThat(clean.guardResult().failedGuards()).isEmpty();
        assertThat(clean.guardResult().passedGuards()).contains("technical-answer-present", "at-least-one-official-source");
    }

    @Test
    void assistedItemIsNotReady() {
        AtFaqGovernedPublicationPlan plan = planService.plan(preCuration, standardReview(), "test-planner");
        AtFaqGovernedPublicationCandidate item = candidateOf(plan, "AT-FAQ-1002");

        assertThat(item.readiness()).isEqualTo(AtFaqGovernedPublicationReadiness.NEEDS_ASSISTED_REVIEW);
        assertThat(item.readyForFuturePublication()).isFalse();
        assertThat(item.requiresAssistedReview()).isTrue();
        assertThat(item.guardResult().failedGuards()).contains("legal-reference-present");
    }

    @Test
    void manualItemIsNotReady() {
        AtFaqGovernedPublicationPlan plan = planService.plan(preCuration, standardReview(), "test-planner");
        AtFaqGovernedPublicationCandidate item = candidateOf(plan, "AT-FAQ-1005");

        assertThat(item.readiness()).isEqualTo(AtFaqGovernedPublicationReadiness.NEEDS_MANUAL_REVIEW);
        assertThat(item.readyForFuturePublication()).isFalse();
        assertThat(item.requiresManualReview()).isTrue();
    }

    @Test
    void rejectedItemIsBlocked() {
        AtFaqGovernedPublicationPlan plan = planService.plan(preCuration, standardReview(), "test-planner");
        AtFaqGovernedPublicationCandidate item = candidateOf(plan, "AT-FAQ-1003");

        assertThat(item.readiness()).isEqualTo(AtFaqGovernedPublicationReadiness.BLOCKED);
        assertThat(item.blocked()).isTrue();
        assertThat(item.readyForFuturePublication()).isFalse();
    }

    @Test
    void itemWithoutTechnicalAnswerIsBlocked() {
        AtFaqGovernedPublicationPlan plan = planService.plan(preCuration, standardReview(), "test-planner");
        AtFaqGovernedPublicationCandidate item = candidateOf(plan, "AT-FAQ-1006");

        assertThat(item.readiness()).isEqualTo(AtFaqGovernedPublicationReadiness.BLOCKED);
        assertThat(item.proposedTechnicalAnswer()).isNull();
        assertThat(item.guardResult().failedGuards()).contains("technical-answer-present");
    }

    @Test
    void conflictItemIsNeverReady() {
        AtFaqGovernedPublicationPlan plan = planService.plan(preCuration, standardReview(), "test-planner");
        AtFaqGovernedPublicationCandidate item = candidateOf(plan, "AT-FAQ-1004");

        assertThat(item.readyForFuturePublication()).isFalse();
        assertThat(item.readiness()).isIn(
                AtFaqGovernedPublicationReadiness.BLOCKED,
                AtFaqGovernedPublicationReadiness.NEEDS_MANUAL_REVIEW);
    }

    @Test
    void deferredDecisionMakesCandidateDeferred() {
        // No decision for AT-FAQ-1001 → E6 marks it deferred → E7 DEFERRED.
        AtFaqReviewResult review = reviewService.review(preCuration, List.of(
                AtFaqReviewDecision.of("AT-FAQ-1002", AtFaqReviewDecisionType.SEND_TO_ASSISTED_REVIEW, "test-reviewer", "Assistida.")),
                "test-reviewer");
        AtFaqGovernedPublicationPlan plan = planService.plan(preCuration, review, "test-planner");
        AtFaqGovernedPublicationCandidate item = candidateOf(plan, "AT-FAQ-1001");

        assertThat(item.readiness()).isEqualTo(AtFaqGovernedPublicationReadiness.DEFERRED);
        assertThat(item.deferred()).isTrue();
        assertThat(item.readyForFuturePublication()).isFalse();
    }

    @Test
    void preCurationItemWithoutReviewIsDeferred() {
        AtFaqReviewResult full = standardReview();
        List<AtFaqReviewItemResult> subset = full.itemResults().stream()
                .filter(i -> !"AT-FAQ-1001".equals(i.externalId()))
                .toList();
        AtFaqReviewResult trimmed = new AtFaqReviewResult(
                full.batchId(), full.reviewedAt(), full.reviewedBy(), full.totals(),
                subset, full.globalWarnings(), full.blockingErrors(), full.nextActions());

        AtFaqGovernedPublicationPlan plan = planService.plan(preCuration, trimmed, "test-planner");
        AtFaqGovernedPublicationCandidate item = candidateOf(plan, "AT-FAQ-1001");

        assertThat(item.readiness()).isEqualTo(AtFaqGovernedPublicationReadiness.DEFERRED);
        assertThat(item.guardResult().failedGuards()).contains("review-decision-present");
    }

    @Test
    void reviewItemWithoutPreCurationRaisesGlobalWarning() {
        AtFaqReviewResult full = standardReview();
        List<AtFaqReviewItemResult> withGhost = new ArrayList<>(full.itemResults());
        withGhost.add(new AtFaqReviewItemResult(
                "AT-FAQ-GHOST", "Pergunta fantasma?",
                AtFaqBatchPublicationPath.AUTO_CONTROLLED,
                AtFaqReviewDecisionType.ACCEPT_AUTO_CONTROLLED_CANDIDATE,
                AtFaqBatchPublicationPath.AUTO_CONTROLLED,
                true, false, false, false, List.of(), List.of(), List.of()));
        AtFaqReviewResult withExtra = new AtFaqReviewResult(
                full.batchId(), full.reviewedAt(), full.reviewedBy(), full.totals(),
                withGhost, full.globalWarnings(), full.blockingErrors(), full.nextActions());

        AtFaqGovernedPublicationPlan plan = planService.plan(preCuration, withExtra, "test-planner");

        assertThat(plan.globalWarnings()).anyMatch(w -> w.contains("AT-FAQ-GHOST"));
        // The ghost is never planned as a candidate.
        assertThat(plan.candidates()).noneMatch(c -> "AT-FAQ-GHOST".equals(c.externalId()));
    }

    @Test
    void guardResultListsPassedAndFailedGuards() {
        AtFaqGovernedPublicationPlan plan = planService.plan(preCuration, standardReview(), "test-planner");
        AtFaqGovernedPublicationCandidate assisted = candidateOf(plan, "AT-FAQ-1002");

        assertThat(assisted.guardResult().passedGuards()).contains("technical-answer-present");
        assertThat(assisted.guardResult().failedGuards()).contains("legal-reference-present");
    }

    @Test
    void candidateCarriesSourceSummariesWithoutRawContent() {
        AtFaqGovernedPublicationPlan plan = planService.plan(preCuration, standardReview(), "test-planner");
        AtFaqGovernedPublicationCandidate clean = candidateOf(plan, "AT-FAQ-1001");

        assertThat(clean.sourceSummaries()).isNotEmpty();
        assertThat(clean.sourceSummaries()).anyMatch(s -> s.contains("OFFICIAL_FAQ"));
        assertThat(clean.sourceSummaries()).allMatch(s -> !s.contains("<") && !s.contains("```"));
    }

    @Test
    void publishedAndIndexedAreAlwaysZero() {
        AtFaqGovernedPublicationPlan plan = planService.plan(preCuration, standardReview(), "test-planner");

        assertThat(plan.totals().published()).isZero();
        assertThat(plan.totals().indexed()).isZero();
    }

    @Test
    void totalsAreConsistentWithCandidates() {
        AtFaqGovernedPublicationPlan plan = planService.plan(preCuration, standardReview(), "test-planner");
        var t = plan.totals();

        assertThat(t.readyForFuturePublication()
                + t.needsAssistedReview()
                + t.needsManualReview()
                + t.blocked()
                + t.deferred())
                .isEqualTo(t.totalItems());
    }

    @Test
    void globalNextActionsReinforceNothingPublished() {
        AtFaqGovernedPublicationPlan plan = planService.plan(preCuration, standardReview(), "test-planner");

        assertThat(plan.nextActions()).anyMatch(a -> a.contains("published=0") && a.contains("indexed=0"));
    }

    @Test
    void executionIsDeterministic() {
        AtFaqReviewResult review = standardReview();
        AtFaqGovernedPublicationPlan first = planService.plan(preCuration, review, "test-planner");
        AtFaqGovernedPublicationPlan second = planService.plan(preCuration, review, "test-planner");

        assertThat(second).isEqualTo(first);
        assertThat(second.candidates()).isEqualTo(first.candidates());
        assertThat(second.plannedAt()).isEqualTo(first.plannedAt());
    }

    @Test
    void outputContainsNoRawHtmlPromptsOrChunks() {
        AtFaqGovernedPublicationPlan plan = planService.plan(preCuration, standardReview(), "test-planner");

        List<String> texts = new ArrayList<>();
        texts.addAll(plan.globalWarnings());
        texts.addAll(plan.blockingErrors());
        texts.addAll(plan.nextActions());
        for (AtFaqGovernedPublicationCandidate c : plan.candidates()) {
            texts.add(c.normalizedQuestion());
            texts.add(c.proposedShortAnswer());
            texts.add(c.proposedTechnicalAnswer());
            texts.addAll(c.proposedLegalReferences());
            texts.addAll(c.sourceSummaries());
            texts.addAll(c.nextActions());
            texts.addAll(c.guardResult().passedGuards());
            texts.addAll(c.guardResult().failedGuards());
            texts.addAll(c.guardResult().warnings());
            texts.addAll(c.guardResult().blockingReasons());
        }
        for (String t : texts) {
            if (t != null) {
                assertThat(t).doesNotContain("<").doesNotContain("```");
                assertThat(t.toLowerCase()).doesNotContain("prompt").doesNotContain("chunk");
            }
        }
    }
}
