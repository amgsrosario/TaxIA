package com.knowledgeflow.rag;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Embeds validated knowledge cases and upserts them into knowledge_case_embeddings.
 * Uses JdbcTemplate directly because Hibernate has no native mapping for the
 * pgvector "vector" column type — the value is passed as a "[...]" literal cast on the SQL side.
 *
 * Disabled in the "test" profile; StubCaseEmbeddingIndexer is used instead to avoid
 * executing PostgreSQL-specific SQL (?::vector) against H2.
 *
 * KNOWN LIMITATION (future work): indexValidatedCase() is called from within the
 * @Transactional validate() method. This means the HTTP call to the embeddings service
 * and the subsequent JDBC write both execute while the database transaction is open,
 * holding a connection from the pool for the full duration of the HTTP round-trip.
 * Under load this can exhaust the connection pool.
 * Recommended next step: publish a domain event on commit and handle indexing in an
 * @TransactionalEventListener(phase = AFTER_COMMIT) that runs outside the transaction,
 * or use a dedicated async/outbox-based indexing pipeline.
 */
@Component
@org.springframework.context.annotation.Profile("!(test | pgtest)")
public class KnowledgeCaseEmbeddingIndexer implements CaseEmbeddingIndexer {

    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;

    public KnowledgeCaseEmbeddingIndexer(EmbeddingService embeddingService, JdbcTemplate jdbcTemplate) {
        this.embeddingService = embeddingService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void indexValidatedCase(UUID knowledgeCaseId, String title, String question, String content) {
        String chunkText = title + "\n\n" + question + (content != null ? "\n\n" + content : "");
        List<Float> embedding = embeddingService.embedPassage(chunkText);

        jdbcTemplate.update("""
                INSERT INTO knowledge_case_embeddings (id, knowledge_case_id, chunk_text, embedding, created_at)
                VALUES (?, ?, ?, ?::vector, ?)
                ON CONFLICT (knowledge_case_id)
                DO UPDATE SET chunk_text = EXCLUDED.chunk_text, embedding = EXCLUDED.embedding
                """,
                UUID.randomUUID(), knowledgeCaseId, chunkText, toVectorLiteral(embedding), OffsetDateTime.now());
    }

    private String toVectorLiteral(List<Float> embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding.get(i));
        }
        return sb.append(']').toString();
    }
}
