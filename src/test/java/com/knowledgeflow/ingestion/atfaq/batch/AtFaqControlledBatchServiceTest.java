package com.knowledgeflow.ingestion.atfaq.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ai.documented.FreshnessStatus;
import com.knowledgeflow.ingestion.atfaq.AtFaqNormalizer;
import com.knowledgeflow.ingestion.atfaq.AtFaqRunMode;
import com.knowledgeflow.ingestion.atfaq.AtFaqRunStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the controlled AT-FAQ batch simulation (Bloco E — E4).
 *
 * <p>Central invariants proven here: the simulation never publishes and never indexes
 * ({@code published == 0}, {@code indexed == 0}), classification is criteria-based with the
 * most-restrictive path winning, and reprocessing the same input is idempotent at the report
 * level. Pure unit test: no Spring context, no database, no HTTP.
 */
class AtFaqControlledBatchServiceTest {

    private AtFaqControlledBatchService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-08-06T10:15:30Z"), ZoneOffset.UTC);
        service = new AtFaqControlledBatchService(new AtFaqNormalizer(), fixedClock);
    }

    private static AtFaqBatchItemSummary summaryOf(AtFaqBatchReport report, String externalId) {
        return report.itemSummaries().stream()
                .filter(s -> externalId.equals(s.externalId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No summary for " + externalId));
    }

    @Test
    void producesCompleteReportForSixItemBatch() {
        AtFaqBatchReport report = service.run("test:complete", ControlledBatchFixtures.sixItemBatch());

        assertThat(report.batchId()).startsWith("atfaq-batch-");
        assertThat(report.mode()).isEqualTo(AtFaqRunMode.DRY_RUN);
        assertThat(report.status()).isEqualTo(AtFaqRunStatus.COMPLETED);
        assertThat(report.itemSummaries()).hasSize(6);
        assertThat(report.totals().discovered()).isEqualTo(6);
        assertThat(report.totals().importedRaw()).isEqualTo(6);
        assertThat(report.totals().preCurated()).isEqualTo(6);
        assertThat(report.totals().failed()).isZero();
        assertThat(report.nextActions()).isNotEmpty();
    }

    @Test
    void neverPublishesOrIndexes() {
        AtFaqBatchReport report = service.run("test:no-publish", ControlledBatchFixtures.sixItemBatch());

        assertThat(report.totals().published()).isZero();
        assertThat(report.totals().indexed()).isZero();
        assertThat(report.itemSummaries())
                .noneMatch(s -> s.proposedPath() == null);
    }

    @Test
    void totalsPartitionEveryItemByPath() {
        AtFaqBatchReport report = service.run("test:partition", ControlledBatchFixtures.sixItemBatch());
        var t = report.totals();

        assertThat(t.autoControlledCandidates()
                + t.assistedCandidates()
                + t.manualRequiredCandidates()
                + t.notPublishable())
                .isEqualTo(t.discovered());
        // rejected mirrors notPublishable in E4.
        assertThat(t.rejected()).isEqualTo(t.notPublishable());
    }

    @Test
    void cleanItemIsAutoControlled() {
        AtFaqBatchReport report = service.run("test:auto", ControlledBatchFixtures.sixItemBatch());
        AtFaqBatchItemSummary clean = summaryOf(report, "AT-FAQ-1001");

        assertThat(clean.proposedPath()).isEqualTo(AtFaqBatchPublicationPath.AUTO_CONTROLLED);
        assertThat(clean.duplicateCandidate()).isFalse();
        assertThat(clean.conflictCandidate()).isFalse();
        assertThat(clean.hasTechnicalAnswer()).isTrue();
        assertThat(clean.hasLegalReference()).isTrue();
        assertThat(clean.officialSource()).isTrue();
    }

    @Test
    void noLegalFoundationItemIsAssisted() {
        AtFaqBatchReport report = service.run("test:assisted", ControlledBatchFixtures.sixItemBatch());
        AtFaqBatchItemSummary item = summaryOf(report, "AT-FAQ-1002");

        assertThat(item.proposedPath()).isEqualTo(AtFaqBatchPublicationPath.ASSISTED);
        assertThat(item.hasLegalReference()).isFalse();
    }

    @Test
    void exactDuplicateIsNotPublishableAndNeverAutoControlled() {
        AtFaqBatchReport report = service.run("test:duplicate", ControlledBatchFixtures.sixItemBatch());
        AtFaqBatchItemSummary duplicate = summaryOf(report, "AT-FAQ-1003");

        assertThat(duplicate.proposedPath()).isEqualTo(AtFaqBatchPublicationPath.NOT_PUBLISHABLE);
        assertThat(duplicate.proposedPath()).isNotEqualTo(AtFaqBatchPublicationPath.AUTO_CONTROLLED);
        assertThat(duplicate.duplicateCandidate()).isTrue();
        assertThat(report.totals().duplicates()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void conflictItemIsManualRequiredAndNeverAutoControlled() {
        AtFaqBatchReport report = service.run("test:conflict", ControlledBatchFixtures.sixItemBatch());
        AtFaqBatchItemSummary conflict = summaryOf(report, "AT-FAQ-1004");

        assertThat(conflict.proposedPath()).isEqualTo(AtFaqBatchPublicationPath.MANUAL_REQUIRED);
        assertThat(conflict.proposedPath()).isNotEqualTo(AtFaqBatchPublicationPath.AUTO_CONTROLLED);
        assertThat(conflict.conflictCandidate()).isTrue();
        assertThat(report.totals().conflicts()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void highRiskItemIsManualRequired() {
        AtFaqBatchReport report = service.run("test:high-risk", ControlledBatchFixtures.sixItemBatch());
        AtFaqBatchItemSummary highRisk = summaryOf(report, "AT-FAQ-1005");

        assertThat(highRisk.proposedPath()).isEqualTo(AtFaqBatchPublicationPath.MANUAL_REQUIRED);
        assertThat(highRisk.proposedRiskLevel()).isEqualTo(KnowledgeRiskLevel.HIGH);
    }

    @Test
    void noTechnicalAnswerItemIsNotPublishable() {
        AtFaqBatchReport report = service.run("test:no-technical", ControlledBatchFixtures.sixItemBatch());
        AtFaqBatchItemSummary item = summaryOf(report, "AT-FAQ-1006");

        assertThat(item.proposedPath()).isEqualTo(AtFaqBatchPublicationPath.NOT_PUBLISHABLE);
        assertThat(item.hasTechnicalAnswer()).isFalse();
    }

    @Test
    void mostRestrictiveWinsWhenSeveralCriteriaApply() {
        // HIGH risk (MANUAL_REQUIRED) AND no technical answer (NOT_PUBLISHABLE) → NOT_PUBLISHABLE.
        AtFaqControlledBatchItem item = new AtFaqControlledBatchItem(
                "AT-FAQ-EDGE",
                "faq://at/local/edge",
                "FAQ AT — edge",
                "Pergunta de alto risco sem resposta técnica?",
                "Resposta original existe.",
                null, // no technical answer
                "IVA",
                KnowledgeRiskLevel.HIGH,
                "Artigo qualquer",
                true,
                FreshnessStatus.CURRENT,
                false,
                false);

        AtFaqBatchReport report = service.run("test:most-restrictive", List.of(item));
        AtFaqBatchItemSummary summary = summaryOf(report, "AT-FAQ-EDGE");

        assertThat(summary.proposedPath()).isEqualTo(AtFaqBatchPublicationPath.NOT_PUBLISHABLE);
    }

    @Test
    void structurallyInvalidItemFailsAndProducesBlockingError() {
        AtFaqControlledBatchItem invalid = new AtFaqControlledBatchItem(
                "AT-FAQ-INVALID",
                "faq://at/local/invalid",
                "FAQ AT — invalid",
                "   ", // blank question
                "",    // blank answer
                "irrelevante",
                "IVA",
                KnowledgeRiskLevel.LOW,
                "Artigo",
                true,
                FreshnessStatus.CURRENT,
                false,
                false);

        AtFaqBatchReport report = service.run("test:invalid", List.of(invalid));
        AtFaqBatchItemSummary summary = summaryOf(report, "AT-FAQ-INVALID");

        assertThat(summary.proposedPath()).isEqualTo(AtFaqBatchPublicationPath.NOT_PUBLISHABLE);
        assertThat(summary.importedRaw()).isFalse();
        assertThat(report.totals().failed()).isEqualTo(1);
        assertThat(report.totals().importedRaw()).isZero();
        assertThat(report.blockingErrors()).isNotEmpty();
    }

    @Test
    void contentHashAndNormalizedQuestionAreStableAcrossRuns() {
        AtFaqBatchReport first = service.run("test:hash-1", ControlledBatchFixtures.sixItemBatch());
        AtFaqBatchReport second = service.run("test:hash-2", ControlledBatchFixtures.sixItemBatch());

        AtFaqBatchItemSummary a = summaryOf(first, "AT-FAQ-1001");
        AtFaqBatchItemSummary b = summaryOf(second, "AT-FAQ-1001");

        assertThat(a.contentHash()).isEqualTo(b.contentHash());
        assertThat(a.contentHash()).hasSize(64); // SHA-256 hex
        assertThat(a.normalizedQuestion()).isEqualTo(b.normalizedQuestion());
        // The clean item and its exact duplicate share the same content hash.
        assertThat(summaryOf(first, "AT-FAQ-1001").contentHash())
                .isEqualTo(summaryOf(first, "AT-FAQ-1003").contentHash());
    }

    @Test
    void batchIdIsDeterministicForSameInput() {
        AtFaqBatchReport first = service.run("test:id-1", ControlledBatchFixtures.sixItemBatch());
        AtFaqBatchReport second = service.run("test:id-2", ControlledBatchFixtures.sixItemBatch());

        assertThat(first.batchId()).isEqualTo(second.batchId());
    }

    @Test
    void reprocessingSameInputIsIdempotentAtReportLevel() {
        // Same input, same (fixed) clock, same triggeredBy → fully identical report.
        AtFaqBatchReport first = service.run("test:idem", ControlledBatchFixtures.sixItemBatch());
        AtFaqBatchReport second = service.run("test:idem", ControlledBatchFixtures.sixItemBatch());

        assertThat(second).isEqualTo(first);
        assertThat(second.itemSummaries()).isEqualTo(first.itemSummaries());
        assertThat(second.totals()).isEqualTo(first.totals());
    }

    @Test
    void emptyBatchProducesEmptyButValidReport() {
        AtFaqBatchReport report = service.run("test:empty", List.of());

        assertThat(report.itemSummaries()).isEmpty();
        assertThat(report.totals().discovered()).isZero();
        assertThat(report.totals().published()).isZero();
        assertThat(report.totals().indexed()).isZero();
        assertThat(report.nextActions()).isNotEmpty(); // always states nothing was published/indexed
    }
}
