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
 */
@Component
public class KnowledgeCaseEmbeddingIndexer {

    private final EmbeddingClient embeddingClient;
    private final JdbcTemplate jdbcTemplate;

    public KnowledgeCaseEmbeddingIndexer(EmbeddingClient embeddingClient, JdbcTemplate jdbcTemplate) {
        this.embeddingClient = embeddingClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void indexValidatedCase(UUID knowledgeCaseId, String title, String question, String content) {
        String chunkText = title + "\n\n" + question + (content != null ? "\n\n" + content : "");
        List<Float> embedding = embeddingClient.embedPassage(chunkText);

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
