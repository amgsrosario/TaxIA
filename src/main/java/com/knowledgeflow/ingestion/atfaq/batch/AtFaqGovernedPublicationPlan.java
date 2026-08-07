package com.knowledgeflow.ingestion.atfaq.batch;

import java.time.Instant;
import java.util.List;

/**
 * A governed publication <b>plan</b> over a pre-curated + reviewed AT-FAQ batch (Bloco E — E7).
 *
 * <p>"Planear publicação não é publicar. É provar que nada passa sem guardas." This plan
 * cross-references the E5 pre-curation ({@link AtFaqPreCurationResult}) and the E6 review
 * ({@link AtFaqReviewResult}), re-applies the future-publication guards and records, per item,
 * whether it could proceed to a later real publication step. It is produced by deterministic
 * rules only — no database, no HTTP, no external AI/LLM, no publication service, no embedding
 * indexer. It carries no embeddings, no persisted {@code KnowledgeQuestionAnswer}, no raw HTML,
 * no chunks and no prompts. It never publishes and never indexes.
 *
 * @param batchId        the E4/E5/E6 batch id this plan is derived from
 * @param plannedAt      planning timestamp
 * @param plannedBy      identifier of who ran the planning (e.g. "test-planner")
 * @param totals         aggregate counters (with {@code published == 0}, {@code indexed == 0})
 * @param candidates     per-item plan entries
 * @param globalWarnings batch-level non-blocking notes (e.g. review items without pre-curation)
 * @param blockingErrors batch-level blocking problems
 * @param nextActions    recommended follow-up (never publication in E7)
 */
public record AtFaqGovernedPublicationPlan(
        String batchId,
        Instant plannedAt,
        String plannedBy,
        AtFaqGovernedPublicationTotals totals,
        List<AtFaqGovernedPublicationCandidate> candidates,
        List<String> globalWarnings,
        List<String> blockingErrors,
        List<String> nextActions) {

    public AtFaqGovernedPublicationPlan {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        globalWarnings = globalWarnings == null ? List.of() : List.copyOf(globalWarnings);
        blockingErrors = blockingErrors == null ? List.of() : List.copyOf(blockingErrors);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
    }
}
