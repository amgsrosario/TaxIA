package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.common.observability.KnowledgeFlowMetrics;
import com.knowledgeflow.rag.EmbeddingProperties;
import com.knowledgeflow.rag.EmbeddingService;
import com.knowledgeflow.rag.RagSearchService;
import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import com.knowledgeflow.rag.RagSearchService.SourceKind;
import com.knowledgeflow.rag.ValidatedCasesPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * M0: os casos VALIDATED não entram na pesquisa RAG na knowledgeflow_pilot nem com a propriedade desligada. Seis casos
 * mais próximos da pergunta do que a Q&A provam que a exclusão é feita na consulta e não depois do topK.
 */
@Testcontainers
class ValidatedCasesExclusionPostgresIT {

    @Container
    static final PostgreSQLContainer<?> pilot = PgContainers.pgvector().withDatabaseName("knowledgeflow_pilot");

    @Container
    static final PostgreSQLContainer<?> other = PgContainers.pgvector().withDatabaseName("outra_base");

    private static final UUID ORG = UUID.randomUUID();

    @Test
    @DisplayName("knowledgeflow_pilot: excluídos mesmo com a propriedade ligada; Q&A encontrada; casos intactos")
    void pilotDatabaseAlwaysExcludes() {
        JdbcTemplate jdbc = seeded(pilot);
        List<RetrievedCase> results = search(jdbc, true);
        assertThat(results).extracting(RetrievedCase::sourceKind).containsExactly(SourceKind.KNOWLEDGE_QA);
        assertCasesUntouched(jdbc);
    }

    @Test
    @DisplayName("outra base: propriedade desligada exclui; ligada inclui (controlo)")
    void otherDatabaseFollowsTheProperty() {
        JdbcTemplate jdbc = seeded(other);
        assertThat(search(jdbc, false)).extracting(RetrievedCase::sourceKind).containsExactly(SourceKind.KNOWLEDGE_QA);
        assertThat(search(jdbc, true)).hasSize(5).extracting(RetrievedCase::sourceKind).containsOnly(SourceKind.DOCUMENT);
        assertCasesUntouched(jdbc);
    }

    private static List<RetrievedCase> search(JdbcTemplate jdbc, boolean enabled) {
        EmbeddingService embedding = new EmbeddingService() {
            @Override public List<Float> embedQuery(String text) { return vector(0f); }
            @Override public List<Float> embedPassage(String text) { return vector(0f); }
        };
        return new RagSearchService(embedding, jdbc, new EmbeddingProperties(null, 5, null, null, null, null, null),
                new KnowledgeFlowMetrics(new SimpleMeterRegistry()),
                new ValidatedCasesPolicy(enabled, jdbc.getDataSource())).findSimilar(ORG, "pergunta");
    }

    private static void assertCasesUntouched(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_cases WHERE status = 'VALIDATED' "
                + "AND deleted_at IS NULL", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_case_embeddings", Integer.class)).isEqualTo(6);
    }

    private static JdbcTemplate seeded(PostgreSQLContainer<?> db) {
        var ds = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        OffsetDateTime now = OffsetDateTime.now();
        UUID client = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations(id, name, created_at, updated_at) VALUES (?, 'Org M0', ?, ?)", ORG, now, now);
        jdbc.update("INSERT INTO clients(id, organization_id, name, status, created_at, updated_at) "
                + "VALUES (?, ?, 'Cliente', 'ACTIVE', ?, ?)", client, ORG, now, now);
        for (int i = 0; i < 6; i++) {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO knowledge_cases(id, organization_id, client_id, title, question, content, status, "
                    + "current_version_number, created_at, updated_at) VALUES (?, ?, ?, ?, 'p', 'c', 'VALIDATED', 1, ?, ?)",
                    id, ORG, client, "Caso " + i, now, now);
            jdbc.update("INSERT INTO knowledge_case_embeddings(id, knowledge_case_id, chunk_text, embedding, created_at) "
                    + "VALUES (?, ?, 'x', ?::vector, NOW())", UUID.randomUUID(), id, vector(0f).toString());
        }
        UUID qa = UUID.randomUUID();
        jdbc.update("INSERT INTO knowledge_question_answers(id, organization_id, original_question, original_answer, "
                + "technical_answer, curation_status, published_at, created_at, updated_at) "
                + "VALUES (?, ?, 'p', 'r', 'r técnica', 'VALIDATED', ?, ?, ?)", qa, ORG, now, now, now);
        jdbc.update("INSERT INTO knowledge_qa_embeddings(knowledge_qa_id, embedding) VALUES (?, ?::vector)",
                qa, vector(0.5f).toString());
        return jdbc;
    }

    private static List<Float> vector(float second) {
        List<Float> v = new java.util.ArrayList<>(Collections.nCopies(768, 0f));
        v.set(0, 1f);
        v.set(1, second);
        return v;
    }
}
