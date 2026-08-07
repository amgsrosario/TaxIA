package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Execution mode for the governed AT-FAQ publication run (Bloco E — E8B.3).
 *
 * <p>This enum intentionally has a single value. E8B.3 exercises the <em>real</em>
 * {@code KnowledgeQuestionAnswerPublicationService.publish(...)} against an isolated
 * Testcontainers database, under the {@code pgtest} Spring profile. In that profile the active
 * {@code KnowledgeQaEmbeddingIndexer} bean is the no-op {@code StubKnowledgeQaEmbeddingIndexer},
 * so publication promotes the draft all the way to {@code publishedAt}/{@code publishedBy} without
 * ever producing a real embedding or touching the RAG index.
 *
 * <p>There is deliberately no production execution mode here: E8B.3 proves governed promotion in
 * isolation; wiring publication into the real pilot flow is a later step (E9+).
 */
public enum AtFaqGovernedPublicationExecutionMode {

    /**
     * Publish in an isolated test database relying on the stub (no-op) embedding indexer.
     * Guarantees: real publication logic runs, zero real embeddings, zero RAG retrievability.
     */
    TEST_ISOLATED_WITH_STUB_INDEXER
}
