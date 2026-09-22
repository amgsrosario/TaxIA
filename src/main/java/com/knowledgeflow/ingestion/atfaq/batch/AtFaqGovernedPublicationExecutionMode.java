package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Execution mode for the governed AT-FAQ publication run (Bloco E — E8B.3 / E9C).
 *
 * <p>Both values describe the <em>same</em> governed run against an <b>isolated</b> Testcontainers
 * database: the executor always exercises the <em>real</em>
 * {@code KnowledgeQuestionAnswerPublicationService.publish(...)}. The only difference is which
 * {@code KnowledgeQaEmbeddingIndexer} the publication service was wired with, which the executor
 * does not assume — it <b>observes the persisted embedding state after publish()</b> and reports the
 * mode that matches the evidence:
 * <ul>
 *   <li>{@link #TEST_ISOLATED_WITH_STUB_INDEXER} — the run produced zero embeddings (the no-op
 *       {@code StubKnowledgeQaEmbeddingIndexer} was active, e.g. the {@code pgtest} default);
 *       publication reached {@code publishedAt}/{@code publishedBy} without any RAG voice;</li>
 *   <li>{@link #TEST_ISOLATED_WITH_SYNCHRONOUS_INDEXER} — the run produced real embeddings because
 *       {@code publish(...)} indexed synchronously through a real indexer; publication and indexing
 *       happened in the same transaction.</li>
 * </ul>
 *
 * <p>There is deliberately no production / pilot execution mode here: both values are isolated-test
 * descriptors and neither creates an executable path against the real pilot base. Wiring publication
 * into the real pilot flow remains a later, separately governed step.
 */
public enum AtFaqGovernedPublicationExecutionMode {

    /**
     * Publish in an isolated test database where publication produced <b>zero</b> real embeddings
     * (stub / no-op indexer active). Guarantees: real publication logic ran, zero RAG retrievability.
     */
    TEST_ISOLATED_WITH_STUB_INDEXER,

    /**
     * Publish in an isolated test database where {@code publish(...)} indexed <b>synchronously</b>
     * through a real indexer, so publication and embedding creation completed together. Still fully
     * isolated: never the real pilot base, never an external embedding provider.
     */
    TEST_ISOLATED_WITH_SYNCHRONOUS_INDEXER
}
