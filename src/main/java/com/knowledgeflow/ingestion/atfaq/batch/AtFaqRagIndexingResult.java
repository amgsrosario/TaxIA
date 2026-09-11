package com.knowledgeflow.ingestion.atfaq.batch;

import java.time.Instant;
import java.util.List;

/**
 * Full report of a governed AT-FAQ RAG indexing run (Bloco E — E9A).
 *
 * <p>Frase-mestra: "Indexar não é escalar. É dar voz controlada a um único conhecimento publicado."
 * This report records that exactly one published, RAG-eligible, LOW-risk Q&amp;A received an
 * embedding in an isolated database — written through the real {@code KnowledgeQaEmbeddingIndexerImpl}
 * SQL but fed by a deterministic in-test embedding (E9A proves the RAG/pgvector cycle, not the real
 * embedding model) — so the RAG can now retrieve it for a semantically compatible question.
 *
 * <p>It carries no embeddings/vectors, no passages, no prompts, no chunks, no raw HTML and no
 * sensitive logs — only governance-relevant state.
 *
 * @param batchId        batch identifier carried from the publication result
 * @param indexedAt      instant the indexing ran (from an injected clock)
 * @param indexedBy      actor that requested the indexing
 * @param mode           execution mode; the only mode is the single-Q&A isolated test
 * @param totals         aggregate counters, capped at a single indexed Q&A
 * @param itemResults    per-Q&A indexing outcomes
 * @param globalWarnings batch-level non-blocking observations
 * @param blockingErrors batch-level blocking errors (empty on a clean run)
 * @param nextActions    recommended follow-ups (E9B small governed batch; E10 rollback/deindex)
 * @param requestedMaxItems batch limit requested by the caller (1 in E9A single mode)
 * @param effectiveMaxItems batch limit actually applied after clamping/validation (1 in E9A)
 */
public record AtFaqRagIndexingResult(
        String batchId,
        Instant indexedAt,
        String indexedBy,
        AtFaqRagIndexingMode mode,
        AtFaqRagIndexingTotals totals,
        List<AtFaqRagIndexingItemResult> itemResults,
        List<String> globalWarnings,
        List<String> blockingErrors,
        List<String> nextActions,
        int requestedMaxItems,
        int effectiveMaxItems) {

    public AtFaqRagIndexingResult {
        itemResults = itemResults == null ? List.of() : List.copyOf(itemResults);
        globalWarnings = globalWarnings == null ? List.of() : List.copyOf(globalWarnings);
        blockingErrors = blockingErrors == null ? List.of() : List.copyOf(blockingErrors);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
        if (requestedMaxItems < 0) {
            throw new IllegalArgumentException("requestedMaxItems must be >= 0");
        }
        if (effectiveMaxItems < 0) {
            throw new IllegalArgumentException("effectiveMaxItems must be >= 0");
        }
    }
}
