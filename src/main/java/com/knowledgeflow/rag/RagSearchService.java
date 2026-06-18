package com.knowledgeflow.rag;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Finds the validated knowledge cases most similar to a question via pgvector
 * cosine distance, scoped to the asking organization so tenants never see each
 * other's knowledge base.
 */
@Service
@EnableConfigurationProperties(EmbeddingProperties.class)
public class RagSearchService {

    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingProperties props;

    public RagSearchService(EmbeddingService embeddingService, JdbcTemplate jdbcTemplate, EmbeddingProperties props) {
        this.embeddingService = embeddingService;
        this.jdbcTemplate = jdbcTemplate;
        this.props = props;
    }

    public List<RetrievedCase> findSimilar(UUID organizationId, String question) {
        List<Float> queryEmbedding = embeddingService.embedQuery(question);
        String vectorLiteral = toVectorLiteral(queryEmbedding);
        int topK = props.topK() > 0 ? props.topK() : 5;

        return jdbcTemplate.query("""
                SELECT kc.title, kc.question, kc.content,
                       1 - (kce.embedding <=> ?::vector) AS similarity
                FROM knowledge_case_embeddings kce
                JOIN knowledge_cases kc ON kc.id = kce.knowledge_case_id
                WHERE kc.organization_id = ? AND kc.status = 'VALIDATED' AND kc.deleted_at IS NULL
                ORDER BY kce.embedding <=> ?::vector
                LIMIT ?
                """,
                (rs, rowNum) -> new RetrievedCase(
                        rs.getString("title"),
                        rs.getString("question"),
                        rs.getString("content"),
                        rs.getDouble("similarity")),
                vectorLiteral, organizationId, vectorLiteral, topK);
    }

    private String toVectorLiteral(List<Float> embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding.get(i));
        }
        return sb.append(']').toString();
    }

    public record RetrievedCase(String title, String question, String content, double similarity) {}
}
