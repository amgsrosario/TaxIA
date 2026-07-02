package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.ai.AIProvider;
import com.knowledgeflow.ai.provider.anthropic.AnthropicProvider;
import com.knowledgeflow.ai.provider.openai.OpenAIProvider;
import com.knowledgeflow.ai.provider.stub.StubAIProvider;
import com.knowledgeflow.rag.EmbeddingClient;
import com.knowledgeflow.rag.EmbeddingService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL integration test suite — validates infrastructure for the pgtest profile.
 * <p>
 * Run with:  mvn verify -Ppgtest
 * Normal unit-test suite (mvn test) never activates this class.
 * <p>
 * Validates (§2 spec — 14 criteria):
 *   1.  Container starts
 *   2.  Spring context starts
 *   3.  Flyway V14 applied
 *   4.  Hibernate ddl-auto=validate passes (implicit — context load would fail otherwise)
 *   5.  pgvector extension installed
 *   6.  vector(768) columns in embeddings tables
 *   7.  HNSW indices exist
 *   8.  Exactly 1 EmbeddingService (stub)
 *   9.  0 OpenAI providers
 *  10.  0 Anthropic providers
 *  11.  No EmbeddingClient (HTTP) in context
 *  12.  No API keys required
 *  13.  Schema isolated (container-specific DB, discarded after tests)
 *  14.  Suite normal without Docker (guaranteed by *IT.java naming convention)
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
class PgTestApplicationContextIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    // =========================================================================
    // §1 — Container and context startup
    // =========================================================================

    @Test
    @DisplayName("TC-PG-01: Testcontainer PostgreSQL inicia")
    void containerIsRunning() {
        assertThat(postgres.isRunning()).isTrue();
    }

    @Test
    @DisplayName("TC-PG-02: Spring context arranca com perfil pgtest")
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    // =========================================================================
    // §2 — Flyway migrations
    // =========================================================================

    @Test
    @DisplayName("TC-PG-03: Flyway aplicou V14 como versão máxima com sucesso")
    void flywayV14AppliedSuccessfully() {
        Integer maxVersion = jdbc.queryForObject(
                "SELECT MAX(installed_rank) FROM flyway_schema_history WHERE success = true",
                Integer.class);
        // All 14 migrations applied (V1 through V14)
        assertThat(maxVersion).isEqualTo(14);
    }

    @Test
    @DisplayName("TC-PG-04: Flyway tabela flyway_schema_history existe e não tem migrações pendentes")
    void flywayNoFailedMigrations() {
        Integer failCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false",
                Integer.class);
        assertThat(failCount).isZero();
    }

    // =========================================================================
    // §3 — pgvector
    // =========================================================================

    @Test
    @DisplayName("TC-PG-05: Extensão vector (pgvector) está instalada")
    void pgvectorExtensionInstalled() {
        String extname = jdbc.queryForObject(
                "SELECT extname FROM pg_extension WHERE extname = 'vector'",
                String.class);
        assertThat(extname).isEqualTo("vector");
    }

    @Test
    @DisplayName("TC-PG-06: Colunas embedding vector(768) existem nas duas tabelas de embeddings")
    void vectorColumnsExist() {
        // Checks both knowledge_case_embeddings and knowledge_qa_embeddings have vector columns
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                JOIN pg_type t ON t.oid = a.atttypid
                WHERE c.relname IN ('knowledge_case_embeddings', 'knowledge_qa_embeddings')
                  AND a.attname = 'embedding'
                  AND t.typname = 'vector'
                """, Integer.class);
        assertThat(count).isEqualTo(2);
    }

    // =========================================================================
    // §4 — HNSW indices
    // =========================================================================

    @Test
    @DisplayName("TC-PG-07: Índices HNSW existem (V13 + V14)")
    void hnswIndicesExist() {
        List<String> hnswIndexNames = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE indexdef ILIKE '%USING hnsw%'",
                String.class);
        assertThat(hnswIndexNames)
                .as("Expected at least 2 HNSW indices (V13: idx_knowledge_case_embeddings_vector, V14: ix_kqae_embedding_hnsw)")
                .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("TC-PG-08: Índice HNSW de V13 usa vector_cosine_ops")
    void hnswV13IndexDefinition() {
        String indexDef = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_knowledge_case_embeddings_vector'",
                String.class);
        assertThat(indexDef)
                .containsIgnoringCase("USING hnsw")
                .containsIgnoringCase("vector_cosine_ops");
    }

    @Test
    @DisplayName("TC-PG-09: Índice HNSW de V14 usa vector_cosine_ops")
    void hnswV14IndexDefinition() {
        String indexDef = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_kqae_embedding_hnsw'",
                String.class);
        assertThat(indexDef)
                .containsIgnoringCase("USING hnsw")
                .containsIgnoringCase("vector_cosine_ops");
    }

    // =========================================================================
    // §5 — Beans activos: EmbeddingService
    // =========================================================================

    @Test
    @DisplayName("TC-PG-10: Exactamente 1 EmbeddingService activo (stub)")
    void exactlyOneEmbeddingService() {
        Map<String, EmbeddingService> services = context.getBeansOfType(EmbeddingService.class);
        assertThat(services).hasSize(1);
        assertThat(services.values().iterator().next())
                .as("Active EmbeddingService must be the stub, not the HTTP client")
                .isNotInstanceOf(EmbeddingClient.class);
    }

    @Test
    @DisplayName("TC-PG-11: EmbeddingClient (HTTP) não está no contexto")
    void embeddingClientAbsent() {
        assertThatThrownBy(() -> context.getBean(EmbeddingClient.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
    }

    // =========================================================================
    // §6 — Beans activos: AI providers
    // =========================================================================

    @Test
    @DisplayName("TC-PG-12: Exactamente 1 AIProvider (stub) — sem OpenAI nem Anthropic reais")
    void onlyStubAIProvider() {
        Map<String, AIProvider> providers = context.getBeansOfType(AIProvider.class);
        assertThat(providers).hasSize(1);
        assertThat(providers.values().iterator().next()).isInstanceOf(StubAIProvider.class);
    }

    @Test
    @DisplayName("TC-PG-13: AnthropicProvider não está no contexto")
    void anthropicProviderAbsent() {
        assertThatThrownBy(() -> context.getBean(AnthropicProvider.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
    }

    @Test
    @DisplayName("TC-PG-14: OpenAIProvider não está no contexto")
    void openAIProviderAbsent() {
        assertThatThrownBy(() -> context.getBean(OpenAIProvider.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
    }
}
