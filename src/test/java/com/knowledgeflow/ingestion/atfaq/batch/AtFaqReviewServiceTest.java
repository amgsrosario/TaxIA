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

/** Pure in-memory tests for the governed E6 review gate. */
class AtFaqReviewServiceTest {

    private AtFaqReviewService service;
    private AtFaqPreCurationResult preCuration;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-06T10:15:30Z"), ZoneOffset.UTC);
        AtFaqNormalizer normalizer = new AtFaqNormalizer();
        AtFaqControlledBatchService batchService = new AtFaqControlledBatchService(normalizer, clock);
        AtFaqPreCurationService preService = new AtFaqPreCurationService(batchService, normalizer, clock);
        service = new AtFaqReviewService(clock);
        preCuration = preService.preCurate(ControlledBatchFixtures.sixItemBatch());
    }

    @Test
    void explicitDecisionsProduceCompleteResultWithoutPublishing() {
        AtFaqReviewResult result = service.review(preCuration, List.of(
                decision("AT-FAQ-1001", AtFaqReviewDecisionType.ACCEPT_AUTO_CONTROLLED_CANDIDATE),
                decision("AT-FAQ-1002", AtFaqReviewDecisionType.SEND_TO_ASSISTED_REVIEW),
                decision("AT-FAQ-1003", AtFaqReviewDecisionType.REJECT),
                decision("AT-FAQ-1004", AtFaqReviewDecisionType.REQUIRE_MANUAL_REVIEW),
                decision("AT-FAQ-1005", AtFaqReviewDecisionType.REQUIRE_MANUAL_REVIEW),
                decision("AT-FAQ-1006", AtFaqReviewDecisionType.REJECT)), "test-reviewer");

        assertThat(result.batchId()).isEqualTo(preCuration.batchId());
        assertThat(result.reviewedAt()).isEqualTo(Instant.parse("2026-08-06T10:15:30Z"));
        assertThat(result.reviewedBy()).isEqualTo("test-reviewer");
        assertThat(result.itemResults()).hasSize(6);
        assertThat(result.totals().decisionsProvided()).isEqualTo(6);
        assertThat(result.totals().published()).isZero();
        assertThat(result.totals().indexed()).isZero();
        assertThat(result.nextActions()).isNotEmpty();
    }

    @Test
    void cleanAutoControlledItemCanBecomeFutureCandidateOnly() {
        AtFaqReviewItemResult item = item(reviewSingle("AT-FAQ-1001",
                AtFaqReviewDecisionType.ACCEPT_AUTO_CONTROLLED_CANDIDATE), "AT-FAQ-1001");

        assertThat(item.acceptedForFuturePublication()).isTrue();
        assertThat(item.resultingPath()).isEqualTo(AtFaqBatchPublicationPath.AUTO_CONTROLLED);
    }

    @Test
    void assistedManualAndNotPublishableCannotBePromotedToAutoControlled() {
        assertBlockedPromotion("AT-FAQ-1002", AtFaqBatchPublicationPath.ASSISTED);
        assertBlockedPromotion("AT-FAQ-1005", AtFaqBatchPublicationPath.MANUAL_REQUIRED);
        assertBlockedPromotion("AT-FAQ-1006", AtFaqBatchPublicationPath.NOT_PUBLISHABLE);
    }

    @Test
    void missingDecisionIsDeferredAndCounted() {
        AtFaqReviewResult result = service.review(preCuration, List.of(), "test-reviewer");

        assertThat(result.itemResults()).allMatch(AtFaqReviewItemResult::deferred);
        assertThat(result.totals().missingDecision()).isEqualTo(6);
        assertThat(result.totals().deferred()).isEqualTo(6);
    }

    @Test
    void duplicateDecisionUsesMostRestrictiveDeterministically() {
        AtFaqReviewResult result = service.review(preCuration, List.of(
                decision("AT-FAQ-1001", AtFaqReviewDecisionType.ACCEPT_AUTO_CONTROLLED_CANDIDATE),
                decision("AT-FAQ-1001", AtFaqReviewDecisionType.REJECT)), "test-reviewer");

        AtFaqReviewItemResult item = item(result, "AT-FAQ-1001");
        assertThat(item.decisionType()).isEqualTo(AtFaqReviewDecisionType.REJECT);
        assertThat(item.resultingPath()).isEqualTo(AtFaqBatchPublicationPath.NOT_PUBLISHABLE);
        assertThat(item.rejected()).isTrue();
        assertThat(result.globalWarnings()).anyMatch(warning -> warning.contains("duplicada"));
    }

    @Test
    void unknownExternalIdProducesGlobalWarning() {
        AtFaqReviewResult result = service.review(preCuration,
                List.of(decision("AT-FAQ-UNKNOWN", AtFaqReviewDecisionType.REJECT)), "test-reviewer");

        assertThat(result.globalWarnings()).anyMatch(warning -> warning.contains("AT-FAQ-UNKNOWN"));
    }

    @Test
    void strongDecisionWithoutReasonProducesWarning() {
        AtFaqReviewDecision invalid = new AtFaqReviewDecision(
                "AT-FAQ-1001", AtFaqReviewDecisionType.REJECT, "test-reviewer", " ", List.of());
        AtFaqReviewItemResult item = item(
                service.review(preCuration, List.of(invalid), "test-reviewer"), "AT-FAQ-1001");

        assertThat(item.warnings()).anyMatch(warning -> warning.contains("reason vazio"));
    }

    @Test
    void stricterDecisionAlwaysPrevailsAndRequiresHumanReview() {
        AtFaqReviewItemResult item = item(reviewSingle("AT-FAQ-1001",
                AtFaqReviewDecisionType.REQUIRE_MANUAL_REVIEW), "AT-FAQ-1001");

        assertThat(item.resultingPath()).isEqualTo(AtFaqBatchPublicationPath.MANUAL_REQUIRED);
        assertThat(item.requiresHumanReview()).isTrue();
        assertThat(item.acceptedForFuturePublication()).isFalse();
    }

    @Test
    void publishedAndIndexedRemainZeroForEveryDecisionSet() {
        for (AtFaqReviewDecisionType type : AtFaqReviewDecisionType.values()) {
            AtFaqReviewResult result = reviewSingle("AT-FAQ-1001", type);
            assertThat(result.totals().published()).isZero();
            assertThat(result.totals().indexed()).isZero();
        }
    }

    @Test
    void fixedClockMakesRepeatedExecutionDeterministic() {
        List<AtFaqReviewDecision> decisions = List.of(
                decision("AT-FAQ-1001", AtFaqReviewDecisionType.ACCEPT_AUTO_CONTROLLED_CANDIDATE));

        assertThat(service.review(preCuration, decisions, "test-reviewer"))
                .isEqualTo(service.review(preCuration, decisions, "test-reviewer"));
    }

    @Test
    void outputContainsNoRawHtmlPromptsOrChunks() {
        AtFaqReviewResult result = service.review(preCuration, List.of(), "test-reviewer");
        List<String> output = new ArrayList<>();
        output.addAll(result.globalWarnings());
        output.addAll(result.blockingErrors());
        output.addAll(result.nextActions());
        result.itemResults().forEach(item -> {
            output.add(item.normalizedQuestion());
            output.addAll(item.reasons());
            output.addAll(item.warnings());
            output.addAll(item.blockingErrors());
        });

        assertThat(output).allMatch(text -> text != null
                && !text.contains("<") && !text.contains("```")
                && !text.toLowerCase().contains("prompt")
                && !text.toLowerCase().contains("chunk"));
    }

    private void assertBlockedPromotion(String externalId, AtFaqBatchPublicationPath expectedPath) {
        AtFaqReviewItemResult item = item(reviewSingle(externalId,
                AtFaqReviewDecisionType.ACCEPT_AUTO_CONTROLLED_CANDIDATE), externalId);
        assertThat(item.blockingErrors()).isNotEmpty();
        assertThat(item.resultingPath()).isEqualTo(expectedPath);
        assertThat(item.acceptedForFuturePublication()).isFalse();
    }

    private AtFaqReviewResult reviewSingle(String externalId, AtFaqReviewDecisionType type) {
        return service.review(preCuration, List.of(decision(externalId, type)), "test-reviewer");
    }

    private static AtFaqReviewDecision decision(String externalId, AtFaqReviewDecisionType type) {
        return AtFaqReviewDecision.of(externalId, type, "test-reviewer", "Decisao governada de teste.");
    }

    private static AtFaqReviewItemResult item(AtFaqReviewResult result, String externalId) {
        return result.itemResults().stream()
                .filter(candidate -> externalId.equals(candidate.externalId()))
                .findFirst().orElseThrow();
    }
}
