package com.knowledgeflow.ingestion.atfaq.batch;

import java.time.Instant;
import java.util.List;

/**
 * Result of the controlled pre-curation of an AT-FAQ batch (Bloco E — E5).
 *
 * <p>"A pré-curadoria prepara o caso. Não o autoriza a responder." This is a set of proposals
 * over the E4 batch, produced by deterministic rules only — no database, no HTTP, no external
 * AI/LLM, no publication, no indexing, no embeddings. It carries no {@code published} and no
 * {@code indexed} field, no raw HTML, no chunks and no prompts.
 *
 * @param batchId       the E4 batch id this pre-curation is derived from (stable for the same input)
 * @param generatedAt   generation timestamp
 * @param totals        aggregate counters
 * @param items         per-item proposals
 * @param globalWarnings batch-level non-blocking notes
 * @param nextActions   recommended follow-up (never publication in E5)
 */
public record AtFaqPreCurationResult(
        String batchId,
        Instant generatedAt,
        AtFaqPreCurationTotals totals,
        List<AtFaqPreCuratedBatchItem> items,
        List<String> globalWarnings,
        List<String> nextActions) {

    public AtFaqPreCurationResult {
        items = items == null ? List.of() : List.copyOf(items);
        globalWarnings = globalWarnings == null ? List.of() : List.copyOf(globalWarnings);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
    }
}
