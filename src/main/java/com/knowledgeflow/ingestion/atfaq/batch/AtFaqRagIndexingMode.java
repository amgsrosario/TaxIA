package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Execution mode for governed AT-FAQ RAG indexing (Bloco E — E9A).
 *
 * <p>A single value, on purpose. E9A only ever gives <em>controlled voice</em> to <b>one</b> already
 * published Q&amp;A, inside an isolated Testcontainers database, using the real
 * {@code KnowledgeQaEmbeddingIndexerImpl} SQL fed by a deterministic in-test embedding — never a
 * recurring job, never a batch, never the real pilot base and never an external embedding provider.
 */
public enum AtFaqRagIndexingMode {

    /** Index exactly one published Q&amp;A in an isolated DB with a deterministic embedding. */
    TEST_ISOLATED_SINGLE_QA
}
