package com.knowledgeflow.rag;

import java.util.UUID;

/**
 * Contract for indexing a validated knowledge case into the vector store.
 * CaseEmbeddingIndexer is the production implementation (pgvector via JdbcTemplate).
 * StubCaseEmbeddingIndexer is used in the "test" profile to avoid PostgreSQL-specific SQL.
 */
public interface CaseEmbeddingIndexer {

    void indexValidatedCase(UUID knowledgeCaseId, String title, String question, String content);
}
