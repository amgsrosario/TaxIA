package com.knowledgeflow.knowledge.rag;

import java.util.UUID;

/**
 * Contract for indexing a validated Q&A pair into the vector store.
 * Production implementation uses pgvector via JdbcTemplate.
 * Stub implementation is used in the test profile.
 */
public interface KnowledgeQaEmbeddingIndexer {

    void index(UUID qaId, String question, String answer, String topic);

    void remove(UUID qaId);
}
