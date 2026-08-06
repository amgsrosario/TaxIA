package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.ingestion.atfaq.AtFaqRunMode;
import com.knowledgeflow.ingestion.atfaq.AtFaqRunStatus;
import java.time.Instant;
import java.util.List;

/**
 * Auditable result of a controlled AT-FAQ batch simulation (Bloco E — E4).
 *
 * <p>"O lote não é uma importação em massa. É uma unidade auditável de governação."
 * This report is that audit unit for a single controlled run over local fixtures. It proves
 * the governance flow (normalize → detect duplicates/conflicts → propose a publication path)
 * <b>without touching the publishable realm</b>: E4 never publishes and never indexes, so
 * {@code totals.published()} and {@code totals.indexed()} are always {@code 0}.
 *
 * <p>Safe to persist/log: no secrets, no raw HTML, no chunks, no prompts.
 *
 * @param batchId       deterministic id derived from the input (stable for the same input)
 * @param mode          run mode (E4 uses {@link AtFaqRunMode#DRY_RUN})
 * @param status        run status
 * @param startedAt     start timestamp
 * @param finishedAt    end timestamp
 * @param triggeredBy   who/what triggered the run (e.g. a test name)
 * @param sourceSystem  logical source (e.g. "controlled-fixture")
 * @param totals        aggregate counters
 * @param warnings      batch-level non-blocking notes
 * @param blockingErrors batch-level blocking errors
 * @param itemSummaries per-item outcomes
 * @param nextActions   recommended follow-up actions (never publication in E4)
 */
public record AtFaqBatchReport(
        String batchId,
        AtFaqRunMode mode,
        AtFaqRunStatus status,
        Instant startedAt,
        Instant finishedAt,
        String triggeredBy,
        String sourceSystem,
        AtFaqBatchReportTotals totals,
        List<String> warnings,
        List<String> blockingErrors,
        List<AtFaqBatchItemSummary> itemSummaries,
        List<String> nextActions) {

    public AtFaqBatchReport {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        blockingErrors = blockingErrors == null ? List.of() : List.copyOf(blockingErrors);
        itemSummaries = itemSummaries == null ? List.of() : List.copyOf(itemSummaries);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
    }
}
