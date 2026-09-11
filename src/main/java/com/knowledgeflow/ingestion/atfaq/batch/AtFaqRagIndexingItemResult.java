package com.knowledgeflow.ingestion.atfaq.batch;

import java.util.List;
import java.util.UUID;

/**
 * Per-Q&amp;A outcome of governed AT-FAQ RAG indexing (Bloco E — E9A).
 *
 * <p>Exactly one Q&amp;A resolves to {@code indexed == true}: the single published, RAG-eligible,
 * LOW-risk case that passed every guard. Its embedding is written through the real
 * {@code KnowledgeQaEmbeddingIndexerImpl} SQL (fed by a deterministic in-test embedding), so
 * {@code embeddingRows == 1} and {@code ragExpectedToRetrieve == true}. Any other published items in
 * the same run are reported {@code indexed == false} (single-Q&amp;A policy defers them to E9B), and
 * guard failures carry {@code blockingReasons}.
 *
 * <p>This record carries no raw vector, no passage/prompt text, no chunk and no HTML — only
 * governance-relevant state and the normalized question for traceability.
 *
 * @param externalId            stable AT-FAQ identifier
 * @param knowledgeQaId         id of the published Q&amp;A, or {@code null}
 * @param eligibleForIndexing   whether every indexing guard passed
 * @param indexed               whether an embedding was written for this Q&amp;A in this run
 * @param embeddingPresent      whether an embedding row exists for this Q&amp;A ({@code embeddingRows >= 1})
 * @param ragExpectedToRetrieve whether RAG should now retrieve this Q&amp;A (implies {@code indexed})
 * @param embeddingRows         embedding rows observed for this Q&amp;A (0 or 1 in E9A)
 * @param normalizedQuestion    normalized question (traceability only; never a vector/prompt)
 * @param warnings              non-blocking observations
 * @param blockingReasons       reasons indexing was refused (empty when indexed / deferred-clean)
 * @param nextActions           recommended follow-ups
 */
public record AtFaqRagIndexingItemResult(
        String externalId,
        UUID knowledgeQaId,
        boolean eligibleForIndexing,
        boolean indexed,
        boolean embeddingPresent,
        boolean ragExpectedToRetrieve,
        int embeddingRows,
        String normalizedQuestion,
        List<String> warnings,
        List<String> blockingReasons,
        List<String> nextActions) {

    public AtFaqRagIndexingItemResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
        if (embeddingRows < 0 || embeddingRows > 1) {
            throw new IllegalArgumentException(
                    "E9A indexes a single Q&A: embeddingRows must be 0 or 1 for " + externalId);
        }
        if (embeddingPresent != (embeddingRows >= 1)) {
            throw new IllegalArgumentException(
                    "embeddingPresent must agree with embeddingRows for " + externalId);
        }
        if (indexed && embeddingRows != 1) {
            throw new IllegalArgumentException(
                    "An indexed Q&A must have exactly one embedding row: " + externalId);
        }
        if (ragExpectedToRetrieve && !indexed) {
            throw new IllegalArgumentException(
                    "RAG retrieval can only be expected for an indexed Q&A: " + externalId);
        }
    }
}
