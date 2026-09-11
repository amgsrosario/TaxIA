package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Execution mode for governed AT-FAQ RAG indexing (Bloco E — E9A/E9B).
 *
 * <p>Both values are deliberately confined to an isolated Testcontainers database, using the real
 * {@code KnowledgeQaEmbeddingIndexerImpl} SQL fed by a deterministic in-test embedding — never a
 * recurring job, never the real pilot base and never an external embedding provider. E9A gives
 * <em>controlled voice</em> to exactly one published Q&amp;A; E9B extends that to a small governed
 * batch under the very same guards — "indexar vários não é escalar livremente".
 */
public enum AtFaqRagIndexingMode {

    /** E9A: index exactly one published Q&amp;A in an isolated DB with a deterministic embedding. */
    TEST_ISOLATED_SINGLE_QA,

    /**
     * E9B: index a small governed batch (more than one, at most three) of published Q&amp;A in an
     * isolated DB with a deterministic embedding — same guards as the single case, plus a hard cap.
     */
    SMALL_BATCH_TEST_ISOLATED
}
