package com.knowledgeflow.ingestion.atfaq.batch;

import java.time.Instant;
import java.util.List;

/**
 * The result of governed AT-FAQ draft materialization (Bloco E — E8A).
 *
 * <p>"Materializar conhecimento não é publicá-lo." This result carries curable
 * {@link AtFaqMaterializationCandidate} drafts assembled from the E7 plan's
 * {@code READY_FOR_FUTURE_PUBLICATION} candidates. It is produced by deterministic rules only —
 * no database (by default), no HTTP, no external AI/LLM, no publication service, no embedding
 * indexer. It contains no embeddings, no raw HTML, no chunks and no prompts. It never publishes
 * and never indexes: {@code totals.published() == 0} and {@code totals.indexed() == 0}.
 *
 * @param batchId        the E4/E5/E6/E7 batch id this materialization is derived from
 * @param materializedAt materialization timestamp
 * @param materializedBy identifier of who ran the materialization
 * @param totals         aggregate counters (with {@code published == 0}, {@code indexed == 0})
 * @param itemResults    per-item outcomes
 * @param globalWarnings batch-level non-blocking notes
 * @param blockingErrors batch-level blocking problems
 * @param nextActions    recommended follow-up (never publication in E8A)
 */
public record AtFaqMaterializationResult(
        String batchId,
        Instant materializedAt,
        String materializedBy,
        AtFaqMaterializationTotals totals,
        List<AtFaqMaterializationItemResult> itemResults,
        List<String> globalWarnings,
        List<String> blockingErrors,
        List<String> nextActions) {

    public AtFaqMaterializationResult {
        itemResults = itemResults == null ? List.of() : List.copyOf(itemResults);
        globalWarnings = globalWarnings == null ? List.of() : List.copyOf(globalWarnings);
        blockingErrors = blockingErrors == null ? List.of() : List.copyOf(blockingErrors);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
    }
}
