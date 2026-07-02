package com.knowledgeflow.knowledge.rag;

import com.knowledgeflow.rag.EmbeddingService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Production implementation: stores Q&A embeddings in knowledge_qa_embeddings via pgvector.
 * Active in all profiles except "test".
 */
@Component
@Profile("!(test | pgtest)")
public class KnowledgeQaEmbeddingIndexerImpl implements KnowledgeQaEmbeddingIndexer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQaEmbeddingIndexerImpl.class);

    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;

    public KnowledgeQaEmbeddingIndexerImpl(EmbeddingService embeddingService, JdbcTemplate jdbcTemplate) {
        this.embeddingService = embeddingService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void index(UUID qaId, String question, String answer, String topic) {
        // Build passage text: question + validated answer
        String passage = buildPassage(question, answer, topic);
        List<Float> embedding = embeddingService.embedPassage(passage);
        String vectorLiteral = toVectorLiteral(embedding);

        jdbcTemplate.update("""
                INSERT INTO knowledge_qa_embeddings (id, knowledge_qa_id, chunk_text, embedding, created_at)
                VALUES (?::uuid, ?::uuid, ?, ?::vector, ?)
                ON CONFLICT (knowledge_qa_id) DO UPDATE
                  SET chunk_text = EXCLUDED.chunk_text,
                      embedding  = EXCLUDED.embedding,
                      created_at = EXCLUDED.created_at
                """,
                UUID.randomUUID().toString(),
                qaId.toString(),
                passage,
                vectorLiteral,
                OffsetDateTime.now());

        log.debug("Indexed QA embedding qaId={}", qaId);
    }

    @Override
    public void remove(UUID qaId) {
        jdbcTemplate.update(
                "DELETE FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                qaId.toString());
        log.debug("Removed QA embedding qaId={}", qaId);
    }

    private String buildPassage(String question, String answer, String topic) {
        StringBuilder sb = new StringBuilder();
        if (topic != null && !topic.isBlank()) {
            sb.append("Tema: ").append(topic).append("\n\n");
        }
        sb.append("Pergunta:\n").append(question).append("\n\n");
        sb.append("Resposta validada:\n").append(answer);
        return sb.toString();
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
