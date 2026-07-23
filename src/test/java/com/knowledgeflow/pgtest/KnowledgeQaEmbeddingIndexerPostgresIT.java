package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.rag.KnowledgeQaEmbeddingIndexerImpl;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.rag.EmbeddingService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the REAL {@link KnowledgeQaEmbeddingIndexerImpl} SQL against a real
 * PostgreSQL + pgvector schema (V14) — the code path that a live publication
 * runs and that the rest of the suite never touches (test/pgtest profiles
 * substitute the indexer with a stub or a @Primary JDBC bean).
 * <p>
 * The indexer bean itself is {@code @Profile("!(test | pgtest)")}, so it is not
 * a Spring bean here; it is instantiated directly with the container-backed
 * JdbcTemplate and a deterministic EmbeddingService, keeping the indexer's own
 * SQL fully real. This test fails against the previous (chunk_text/created_at)
 * INSERT and passes once the columns match the V14 schema.
 *
 * Run: mvn verify -Ppgtest -Dit.test=KnowledgeQaEmbeddingIndexerPostgresIT
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KnowledgeQaEmbeddingIndexerPostgresIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final int DIM = 768;

    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired KnowledgeQuestionAnswerRepository qaRepository;

    private KnowledgeQaEmbeddingIndexerImpl indexer;
    private UUID qaId;

    /** Deterministic embedding: first component set, remaining zero. */
    private static EmbeddingService fixedEmbedding(float first) {
        List<Float> vec = new ArrayList<>(DIM);
        vec.add(first);
        for (int i = 1; i < DIM; i++) vec.add(0.0f);
        List<Float> immutable = List.copyOf(vec);
        return new EmbeddingService() {
            @Override public List<Float> embedQuery(String text) { return immutable; }
            @Override public List<Float> embedPassage(String text) { return immutable; }
        };
    }

    @BeforeAll
    void setUp() {
        Organization org = organizationRepository.save(new Organization("Org Indexer IT", null));
        KnowledgeQuestionAnswer qa = qaRepository.save(new KnowledgeQuestionAnswer(
                org, "Pergunta de teste do indexer?", "Resposta original.", "indexer-it", "IDX-001"));
        qaId = qa.getId();
    }

    private long embeddingCountFor(UUID id) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                Long.class, id.toString());
        return count == null ? 0 : count;
    }

    @Test
    @Order(1)
    @DisplayName("index() cria exactamente 1 linha com o schema real (V14) — sem chunk_text")
    void index_createsExactlyOneRow() {
        indexer = new KnowledgeQaEmbeddingIndexerImpl(fixedEmbedding(1.0f), jdbc);

        indexer.index(qaId, "Pergunta de teste do indexer?", "Resposta validada.", "IVA");

        assertThat(embeddingCountFor(qaId)).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT embedding, indexed_at FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                qaId.toString());
        assertThat(row.get("embedding")).isNotNull();
        assertThat(row.get("indexed_at")).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("index() de novo faz upsert por knowledge_qa_id — não duplica")
    void index_secondCallUpserts() {
        var before = jdbc.queryForMap(
                "SELECT indexed_at FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                qaId.toString());

        // Novo vector determinístico diferente do primeiro.
        KnowledgeQaEmbeddingIndexerImpl reindexer =
                new KnowledgeQaEmbeddingIndexerImpl(fixedEmbedding(0.5f), jdbc);
        reindexer.index(qaId, "Pergunta de teste do indexer?", "Resposta validada v2.", "IVA");

        // Continua exactamente 1 linha — a constraint UNIQUE de knowledge_qa_id
        // faria falhar um INSERT sem o ON CONFLICT.
        assertThat(embeddingCountFor(qaId)).isEqualTo(1);

        var after = jdbc.queryForMap(
                "SELECT indexed_at FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                qaId.toString());
        assertThat(after.get("indexed_at")).isNotNull();
        assertThat(before.get("indexed_at")).isNotNull();
    }

    @Test
    @Order(3)
    @DisplayName("remove() apaga a linha do embedding")
    void remove_deletesRow() {
        indexer = new KnowledgeQaEmbeddingIndexerImpl(fixedEmbedding(1.0f), jdbc);

        indexer.remove(qaId);

        assertThat(embeddingCountFor(qaId)).isZero();
    }
}
