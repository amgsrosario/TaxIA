package com.knowledgeflow.rag;

import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(RagSearchService.class);

    private static final String CASES_BRANCH = """
            SELECT kc.title,
                   kc.question,
                   kc.content,
                   1 - (kce.embedding <=> ?::vector) AS similarity,
                   'DOCUMENT'     AS source_kind,
                   NULL           AS source_qa_id
            FROM knowledge_case_embeddings kce
            JOIN knowledge_cases kc ON kc.id = kce.knowledge_case_id
            WHERE kc.organization_id = ?
              AND kc.status = 'VALIDATED'
              AND kc.deleted_at IS NULL
              AND current_database() <> 'knowledgeflow_pilot'
            UNION ALL
            """;

    private static final String QA_BRANCH = """
            SELECT COALESCE(kqa.normalized_question, kqa.original_question) AS title,
                   kqa.original_question   AS question,
                   COALESCE(kqa.technical_answer, kqa.short_answer)         AS content,
                   1 - (kqae.embedding <=> ?::vector)                       AS similarity,
                   'KNOWLEDGE_QA' AS source_kind,
                   kqa.id::text   AS source_qa_id
            FROM knowledge_qa_embeddings kqae
            JOIN knowledge_question_answers kqa ON kqa.id = kqae.knowledge_qa_id
            WHERE kqa.organization_id = ?
              AND kqa.curation_status = 'VALIDATED'
              AND kqa.published_at IS NOT NULL
              AND kqa.technical_answer IS NOT NULL
              AND (kqa.valid_to IS NULL OR kqa.valid_to >= CURRENT_DATE)
              AND (kqa.valid_from IS NULL OR kqa.valid_from <= CURRENT_DATE)
            ORDER BY similarity DESC
            LIMIT ?
            """;

    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingProperties props;
    private final com.knowledgeflow.common.observability.KnowledgeFlowMetrics metrics;
    private final ValidatedCasesPolicy validatedCasesPolicy;

    public RagSearchService(EmbeddingService embeddingService, JdbcTemplate jdbcTemplate,
            EmbeddingProperties props,
            com.knowledgeflow.common.observability.KnowledgeFlowMetrics metrics,
            ValidatedCasesPolicy validatedCasesPolicy) {
        this.embeddingService = embeddingService;
        this.jdbcTemplate = jdbcTemplate;
        this.props = props;
        this.metrics = metrics;
        this.validatedCasesPolicy = validatedCasesPolicy;
    }

    public List<RetrievedCase> findSimilar(UUID organizationId, String question) {
        if (organizationId == null) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "organizationId is required for RAG search");
        }
        long start = System.currentTimeMillis();
        log.debug("RAG search start — org: {}, questionLength: {}", organizationId, question.length());

        List<Float> queryEmbedding = embeddingService.embedQuery(question);
        log.debug("RAG embedding obtained — dimensions: {}, elapsed: {}ms",
                queryEmbedding.size(), System.currentTimeMillis() - start);

        String vectorLiteral = toVectorLiteral(queryEmbedding);
        int topK = props.topK() > 0 ? props.topK() : 5;

        // M0: com os casos excluídos, o ramo dos casos nem chega à consulta (não ocupa lugares do topK). O ramo repete a
        // recusa na própria ligação (current_database), para o caso de a ligação mudar depois do arranque.
        boolean withCases = validatedCasesPolicy.included();
        List<Object> params = new ArrayList<>();
        if (withCases) {
            params.add(vectorLiteral);
            params.add(organizationId);
        }
        params.add(vectorLiteral);
        params.add(organizationId);
        params.add(topK);

        List<RetrievedCase> results = jdbcTemplate.query(withCases ? CASES_BRANCH + QA_BRANCH : QA_BRANCH,
                (rs, rowNum) -> new RetrievedCase(
                        rs.getString("title"),
                        rs.getString("question"),
                        rs.getString("content"),
                        rs.getDouble("similarity"),
                        SourceKind.valueOf(rs.getString("source_kind")),
                        rs.getString("source_qa_id") != null
                                ? UUID.fromString(rs.getString("source_qa_id")) : null),
                params.toArray());

        metrics.recordRagQuery(System.currentTimeMillis() - start);
        log.debug("RAG search complete — org: {}, results: {}, totalElapsed: {}ms",
                organizationId, results.size(), System.currentTimeMillis() - start);
        return results;
    }

    private String toVectorLiteral(List<Float> embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding.get(i));
        }
        return sb.append(']').toString();
    }

    public enum SourceKind { DOCUMENT, KNOWLEDGE_QA }

    public record RetrievedCase(
            String title,
            String question,
            String content,
            double similarity,
            SourceKind sourceKind,
            UUID sourceQaId) {

        /** Backward-compatible constructor for existing test callsites — defaults to DOCUMENT. */
        public RetrievedCase(String title, String question, String content, double similarity) {
            this(title, question, content, similarity, SourceKind.DOCUMENT, null);
        }
    }
}
