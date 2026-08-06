package com.knowledgeflow.ingestion.atfaq.batch;

import java.time.Instant;
import java.util.List;

/**
 * Result of a governed review over a pre-curated AT-FAQ batch (Bloco E — E6).
 *
 * <p>"Rever não é publicar. É decidir o próximo portão." This records, per item, the next gate
 * decided by the reviewers. It is produced by deterministic rules only — no database, no HTTP,
 * no external AI/LLM. It carries no embeddings, no persisted {@code KnowledgeQuestionAnswer},
 * no raw HTML, no chunks and no prompts. It never publishes and never indexes.
 *
 * @param batchId        the E4/E5 batch id this review is derived from
 * @param reviewedAt     review timestamp
 * @param reviewedBy     identifier of who ran the review (e.g. "test-reviewer")
 * @param totals         aggregate counters (with {@code published == 0}, {@code indexed == 0})
 * @param itemResults    per-item review outcomes
 * @param globalWarnings batch-level non-blocking notes (e.g. decisions for unknown ids)
 * @param blockingErrors batch-level blocking problems
 * @param nextActions    recommended follow-up (never publication in E6)
 */
public record AtFaqReviewResult(
        String batchId,
        Instant reviewedAt,
        String reviewedBy,
        AtFaqReviewTotals totals,
        List<AtFaqReviewItemResult> itemResults,
        List<String> globalWarnings,
        List<String> blockingErrors,
        List<String> nextActions) {

    public AtFaqReviewResult {
        itemResults = itemResults == null ? List.of() : List.copyOf(itemResults);
        globalWarnings = globalWarnings == null ? List.of() : List.copyOf(globalWarnings);
        blockingErrors = blockingErrors == null ? List.of() : List.copyOf(blockingErrors);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
    }
}
