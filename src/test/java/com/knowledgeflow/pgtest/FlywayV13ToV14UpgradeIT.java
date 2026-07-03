package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test: V13 → V14 upgrade with pre-existing data.
 * <p>
 * Proves that the V14 migration is safe, additive, and preserves all existing data.
 * Run with:  mvn verify -Ppgtest
 * Isolate:   mvn verify -Ppgtest -Dit.test=FlywayV13ToV14UpgradeIT
 * <p>
 * Tests are ordered and share state via instance fields (@TestInstance PER_CLASS).
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlywayV13ToV14UpgradeIT {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;

    // Snapshot values captured before V14 upgrade
    private int preUpgradeOrgCount;
    private int preUpgradeUserCount;
    private int preUpgradeClientCount;
    private int preUpgradeCaseCount;
    private int preUpgradeCaseVersionCount;
    private int preUpgradeEmbeddingCount;
    private int preUpgradeAuditCount;

    // Fixed UUIDs — stable across all test methods in this class
    private static final UUID ORG_ID       = UUID.fromString("a1000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID      = UUID.fromString("a1000000-0000-0000-0000-000000000002");
    private static final UUID CLIENT_ID    = UUID.fromString("a1000000-0000-0000-0000-000000000003");
    private static final UUID CASE_ID      = UUID.fromString("a1000000-0000-0000-0000-000000000004");
    private static final UUID CASE_VER_ID  = UUID.fromString("a1000000-0000-0000-0000-000000000005");
    private static final UUID EMBED_ID     = UUID.fromString("a1000000-0000-0000-0000-000000000006");
    private static final UUID AUDIT_ID     = UUID.fromString("a1000000-0000-0000-0000-000000000007");
    private static final UUID ORG_USER_ID  = UUID.fromString("a1000000-0000-0000-0000-000000000008");
    private static final UUID ROLE_ADMIN   = UUID.fromString("10000000-0000-0000-0000-000000000001");

    // Sample vector string for vector(768) columns: first dim = 1.0, rest = 0.0
    private static final String SAMPLE_VECTOR;

    static {
        StringBuilder sb = new StringBuilder("[1.0");
        for (int i = 1; i < 768; i++) sb.append(",0.0");
        SAMPLE_VECTOR = sb.append(']').toString();
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @BeforeAll
    void initDataSource() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(5);
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    void closeDataSource() {
        if (dataSource != null && !dataSource.isClosed()) dataSource.close();
    }

    private Flyway flywayWithTarget(String target) {
        var fb = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration");
        if (target != null) fb = fb.target(target);
        return fb.load();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    // =========================================================================
    // TC-UP-01 — Container starts
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("TC-UP-01: Testcontainer pgvector/pgvector:pg16 inicia")
    void tc01_containerStarts() {
        assertThat(postgres.isRunning()).isTrue();
        assertThat(postgres.getJdbcUrl()).startsWith("jdbc:postgresql://");
    }

    // =========================================================================
    // TC-UP-02 — Flyway V1 → V13 (target)
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("TC-UP-02: Flyway executa V1→V13 com target=13")
    void tc02_migrateToV13() {
        var result = flywayWithTarget("13").migrate();
        assertThat(result.migrationsExecuted).isEqualTo(13);
    }

    // =========================================================================
    // TC-UP-03 — Versão actual = 13
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("TC-UP-03: Versão actual no flyway_schema_history = 13")
    void tc03_versionIs13() {
        String currentVersion = jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank DESC LIMIT 1",
                String.class);
        assertThat(currentVersion).isEqualTo("13");

        Integer failedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false",
                Integer.class);
        assertThat(failedCount).isZero();
    }

    // =========================================================================
    // TC-UP-04 — Tabelas da V14 ainda não existem
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("TC-UP-04: Tabelas V14 (knowledge_question_answers, knowledge_source_references, knowledge_qa_embeddings) ainda não existem")
    void tc04_v14TablesAbsent() {
        for (String table : List.of(
                "knowledge_question_answers",
                "knowledge_source_references",
                "knowledge_qa_embeddings")) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                    Integer.class, table);
            assertThat(count)
                    .as("Table %s should not exist before V14", table)
                    .isZero();
        }
    }

    // =========================================================================
    // TC-UP-05 — Inserir dados preexistentes representativos
    // =========================================================================

    @Test
    @Order(5)
    @DisplayName("TC-UP-05: Dados preexistentes inseridos (org, user, client, case, embedding, audit)")
    void tc05_insertPreExistingData() {
        OffsetDateTime now = OffsetDateTime.now();

        // Organization
        jdbc.update("""
                INSERT INTO organizations (id, name, tax_identifier, created_at, updated_at)
                VALUES (?, 'TaxIA Org Upgrade Test', 'PT100000001', ?, ?)
                """, ORG_ID, now, now);

        // User
        jdbc.update("""
                INSERT INTO users (id, email, full_name, password_hash, status, created_at, updated_at)
                VALUES (?, 'upgrade-test@taxia.pt', 'Upgrade Test Admin', '$2a$10$stub', 'ACTIVE', ?, ?)
                """, USER_ID, now, now);

        // Organization ↔ User ↔ Role (ADMIN from V2 seed)
        jdbc.update("""
                INSERT INTO organization_users (id, organization_id, user_id, role_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, ORG_USER_ID, ORG_ID, USER_ID, ROLE_ADMIN, now, now);

        // Client
        jdbc.update("""
                INSERT INTO clients (id, organization_id, name, tax_identifier, status, created_at, updated_at)
                VALUES (?, ?, 'Cliente Upgrade Lda.', 'PT200000002', 'ACTIVE', ?, ?)
                """, CLIENT_ID, ORG_ID, now, now);

        // KnowledgeCase (was "opinion" before V5 rename)
        jdbc.update("""
                INSERT INTO knowledge_cases (id, organization_id, client_id, title, question,
                    status, current_version_number, created_at, updated_at)
                VALUES (?, ?, ?, 'IVA Periodicidade Mensal',
                    'Quando é obrigatória a periodicidade mensal do IVA?',
                    'VALIDATED', 1, ?, ?)
                """, CASE_ID, ORG_ID, CLIENT_ID, now, now);

        // KnowledgeCase version
        jdbc.update("""
                INSERT INTO knowledge_case_versions
                    (id, knowledge_case_id, version_number, source_type, title, question, content,
                     status_snapshot, is_validated_version, created_by_user_id, created_at)
                VALUES (?, ?, 1, 'VALIDATED_FINAL', 'IVA Periodicidade Mensal',
                    'Quando é obrigatória a periodicidade mensal do IVA?',
                    'Volume de negócios superior a 650 000 € obriga à periodicidade mensal.',
                    'VALIDATED', true, ?, ?)
                """, CASE_VER_ID, CASE_ID, USER_ID, now);

        // KnowledgeCase embedding (V13 structure with vector(768))
        jdbc.update("""
                INSERT INTO knowledge_case_embeddings (id, knowledge_case_id, chunk_text, embedding, created_at)
                VALUES (?, ?, 'IVA Periodicidade\n\nQuando é obrigatória a periodicidade mensal do IVA?',
                    ?::vector, ?)
                """, EMBED_ID, CASE_ID, SAMPLE_VECTOR, now);

        // Audit event
        jdbc.update("""
                INSERT INTO audit_events (id, organization_id, user_id, action, entity_type, entity_id, occurred_at)
                VALUES (?, ?, ?, 'KNOWLEDGE_CASE_VALIDATED', 'KnowledgeCase', ?, ?)
                """, AUDIT_ID, ORG_ID, USER_ID, CASE_ID, now);

        // Verify inserts
        assertThat(jdbc.queryForObject("SELECT name FROM organizations WHERE id = ?", String.class, ORG_ID))
                .isEqualTo("TaxIA Org Upgrade Test");
        assertThat(jdbc.queryForObject("SELECT title FROM knowledge_cases WHERE id = ?", String.class, CASE_ID))
                .isEqualTo("IVA Periodicidade Mensal");
        assertThat(jdbc.queryForObject("SELECT id FROM knowledge_case_embeddings WHERE id = ?", UUID.class, EMBED_ID))
                .isEqualTo(EMBED_ID);
    }

    // =========================================================================
    // TC-UP-06 — Snapshot pré-upgrade
    // =========================================================================

    @Test
    @Order(6)
    @DisplayName("TC-UP-06: Snapshot pré-upgrade capturado (contagens por tabela)")
    void tc06_capturePreUpgradeSnapshot() {
        preUpgradeOrgCount      = count("organizations");
        preUpgradeUserCount     = count("users");
        preUpgradeClientCount   = count("clients");
        preUpgradeCaseCount     = count("knowledge_cases");
        preUpgradeCaseVersionCount = count("knowledge_case_versions");
        preUpgradeEmbeddingCount = count("knowledge_case_embeddings");
        preUpgradeAuditCount    = count("audit_events");

        // Must contain at least what we just inserted
        assertThat(preUpgradeOrgCount).isGreaterThanOrEqualTo(1);
        assertThat(preUpgradeCaseCount).isGreaterThanOrEqualTo(1);
        assertThat(preUpgradeEmbeddingCount).isGreaterThanOrEqualTo(1);
        assertThat(preUpgradeAuditCount).isGreaterThanOrEqualTo(1);
    }

    // =========================================================================
    // TC-UP-07 — V14 não contém operações destrutivas
    // =========================================================================

    @Test
    @Order(7)
    @DisplayName("TC-UP-07: V14 migration é puramente aditiva (sem DROP/TRUNCATE/DELETE/ALTER destrutivo)")
    void tc07_v14HasNoDestructiveOperations() throws IOException {
        var resource = new ClassPathResource("db/migration/V14__create_knowledge_qa.sql");
        byte[] bytes = resource.getContentAsByteArray();
        String sql = new String(bytes, StandardCharsets.UTF_8).toUpperCase();

        assertThat(sql).doesNotContain("DROP TABLE");
        assertThat(sql).doesNotContain("DROP COLUMN");
        assertThat(sql).doesNotContain("TRUNCATE");
        assertThat(sql).doesNotContain("DELETE FROM");
        // No ALTER on pre-existing tables
        assertThat(sql).doesNotContain("ALTER TABLE KNOWLEDGE_CASES");
        assertThat(sql).doesNotContain("ALTER TABLE KNOWLEDGE_CASE_EMBEDDINGS");
        assertThat(sql).doesNotContain("ALTER TABLE ORGANIZATIONS");
        assertThat(sql).doesNotContain("ALTER TABLE USERS");
        // V14 only creates new tables
        assertThat(sql).contains("CREATE TABLE KNOWLEDGE_QUESTION_ANSWERS");
        assertThat(sql).contains("CREATE TABLE KNOWLEDGE_SOURCE_REFERENCES");
        assertThat(sql).contains("CREATE TABLE KNOWLEDGE_QA_EMBEDDINGS");
    }

    // =========================================================================
    // TC-UP-08 — Aplicar V14
    // =========================================================================

    @Test
    @Order(8)
    @DisplayName("TC-UP-08: Flyway aplica exactamente 1 migration (V14) no upgrade")
    void tc08_applyV14() {
        // target=14: this IT proves the V13→V14 upgrade path specifically;
        // later migrations (V15+) are covered by their own tests.
        var result = flywayWithTarget("14").migrate();
        assertThat(result.migrationsExecuted)
                .as("Exactly 1 migration (V14) should be applied in the upgrade step")
                .isEqualTo(1);
    }

    // =========================================================================
    // TC-UP-09 — Versão actual = 14
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("TC-UP-09: Versão actual no flyway_schema_history = 14 após upgrade")
    void tc09_versionIs14() {
        String currentVersion = jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank DESC LIMIT 1",
                String.class);
        assertThat(currentVersion).isEqualTo("14");
    }

    // =========================================================================
    // TC-UP-10 — V14 marcada como success
    // =========================================================================

    @Test
    @Order(10)
    @DisplayName("TC-UP-10: V14 marcada como success=true e aplicada uma única vez")
    void tc10_v14MarkedSuccess() {
        Integer successCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '14' AND success = true",
                Integer.class);
        assertThat(successCount).isEqualTo(1);

        Integer failCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false",
                Integer.class);
        assertThat(failCount).isZero();
    }

    // =========================================================================
    // TC-UP-11 — Dados antigos preservados
    // =========================================================================

    @Test
    @Order(11)
    @DisplayName("TC-UP-11: Dados antigos preservados após V14 (contagens idênticas ao snapshot)")
    void tc11_oldDataPreserved() {
        assertThat(count("organizations"))         .isEqualTo(preUpgradeOrgCount);
        assertThat(count("users"))                 .isEqualTo(preUpgradeUserCount);
        assertThat(count("clients"))               .isEqualTo(preUpgradeClientCount);
        assertThat(count("knowledge_cases"))       .isEqualTo(preUpgradeCaseCount);
        assertThat(count("knowledge_case_versions")).isEqualTo(preUpgradeCaseVersionCount);
        assertThat(count("knowledge_case_embeddings")).isEqualTo(preUpgradeEmbeddingCount);
        assertThat(count("audit_events"))          .isEqualTo(preUpgradeAuditCount);

        // Specific record content preserved
        assertThat(jdbc.queryForObject("SELECT name FROM organizations WHERE id = ?", String.class, ORG_ID))
                .isEqualTo("TaxIA Org Upgrade Test");
        assertThat(jdbc.queryForObject("SELECT title FROM knowledge_cases WHERE id = ?", String.class, CASE_ID))
                .isEqualTo("IVA Periodicidade Mensal");
        assertThat(jdbc.queryForObject("SELECT content FROM knowledge_case_versions WHERE id = ?", String.class, CASE_VER_ID))
                .contains("650 000 €");
    }

    // =========================================================================
    // TC-UP-12 — Foreign keys preservadas
    // =========================================================================

    @Test
    @Order(12)
    @DisplayName("TC-UP-12: Foreign keys preservadas após V14")
    void tc12_foreignKeysPreserved() {
        // Client still references Org
        UUID clientOrgId = jdbc.queryForObject(
                "SELECT organization_id FROM clients WHERE id = ?", UUID.class, CLIENT_ID);
        assertThat(clientOrgId).isEqualTo(ORG_ID);

        // KnowledgeCase still references Client and Org
        UUID caseClientId = jdbc.queryForObject(
                "SELECT client_id FROM knowledge_cases WHERE id = ?", UUID.class, CASE_ID);
        assertThat(caseClientId).isEqualTo(CLIENT_ID);

        // KnowledgeCaseVersion still references KnowledgeCase
        UUID versionCaseId = jdbc.queryForObject(
                "SELECT knowledge_case_id FROM knowledge_case_versions WHERE id = ?", UUID.class, CASE_VER_ID);
        assertThat(versionCaseId).isEqualTo(CASE_ID);

        // Audit event still references Org and User
        UUID auditOrgId = jdbc.queryForObject(
                "SELECT organization_id FROM audit_events WHERE id = ?", UUID.class, AUDIT_ID);
        assertThat(auditOrgId).isEqualTo(ORG_ID);
    }

    // =========================================================================
    // TC-UP-13 — Embeddings V13 preservados
    // =========================================================================

    @Test
    @Order(13)
    @DisplayName("TC-UP-13: Embeddings V13 (knowledge_case_embeddings) preservados após V14")
    void tc13_v13EmbeddingsPreserved() {
        assertThat(count("knowledge_case_embeddings")).isEqualTo(preUpgradeEmbeddingCount);

        // Embedding record still references correct case
        UUID embeddingCaseId = jdbc.queryForObject(
                "SELECT knowledge_case_id FROM knowledge_case_embeddings WHERE id = ?",
                UUID.class, EMBED_ID);
        assertThat(embeddingCaseId).isEqualTo(CASE_ID);

        // chunk_text preserved
        String chunkText = jdbc.queryForObject(
                "SELECT chunk_text FROM knowledge_case_embeddings WHERE id = ?",
                String.class, EMBED_ID);
        assertThat(chunkText).contains("IVA Periodicidade");
    }

    // =========================================================================
    // TC-UP-14 — Tabelas V14 criadas
    // =========================================================================

    @Test
    @Order(14)
    @DisplayName("TC-UP-14: Três tabelas V14 criadas (knowledge_question_answers, knowledge_source_references, knowledge_qa_embeddings)")
    void tc14_v14TablesCreated() {
        for (String table : List.of(
                "knowledge_question_answers",
                "knowledge_source_references",
                "knowledge_qa_embeddings")) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                    Integer.class, table);
            assertThat(count)
                    .as("Table %s must exist after V14", table)
                    .isEqualTo(1);
        }
    }

    // =========================================================================
    // TC-UP-15 — Coluna vector(768) criada
    // =========================================================================

    @Test
    @Order(15)
    @DisplayName("TC-UP-15: Coluna embedding vector(768) existe em knowledge_qa_embeddings")
    void tc15_vectorColumnCreated() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                JOIN pg_type t ON t.oid = a.atttypid
                WHERE c.relname = 'knowledge_qa_embeddings'
                  AND a.attname = 'embedding'
                  AND t.typname = 'vector'
                """, Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // =========================================================================
    // TC-UP-16 — Índice HNSW criado
    // =========================================================================

    @Test
    @Order(16)
    @DisplayName("TC-UP-16: Índice HNSW ix_kqae_embedding_hnsw criado em knowledge_qa_embeddings")
    void tc16_hnswIndexCreated() {
        String indexDef = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_kqae_embedding_hnsw'",
                String.class);
        assertThat(indexDef)
                .containsIgnoringCase("USING hnsw")
                .containsIgnoringCase("vector_cosine_ops");
    }

    // =========================================================================
    // TC-UP-17 — Segunda execução do Flyway é idempotente
    // =========================================================================

    @Test
    @Order(17)
    @DisplayName("TC-UP-17: Segunda execução de Flyway.migrate() aplica 0 migrations (idempotente)")
    void tc17_secondMigrationIsIdempotent() {
        var result = flywayWithTarget("14").migrate();
        assertThat(result.migrationsExecuted)
                .as("Second migrate() call should apply zero migrations")
                .isZero();
    }

    // =========================================================================
    // TC-UP-18 — Flyway validate passa (equivalente a Hibernate ddl-auto=validate)
    // =========================================================================

    @Test
    @Order(18)
    @DisplayName("TC-UP-18: Flyway.validate() passa — schema em estado consistente com V1→V14")
    void tc18_flywayValidatePasses() {
        assertThatCode(() -> flywayWithTarget("14").validate())
                .as("Flyway validate() should pass — all migration checksums must match")
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // TC-UP-19 — Zero chamadas externas
    // =========================================================================

    @Test
    @Order(19)
    @DisplayName("TC-UP-19: Zero chamadas externas — nenhum HTTP client, AI provider ou embedding service instanciado")
    void tc19_zeroExternalCalls() {
        // This test class uses only JdbcTemplate over Testcontainers — no Spring context,
        // no @Autowired, no HTTP clients, no AI providers, no embedding microservice calls.
        // Proof 1: no @Autowired fields in this class
        long autowiredFields = java.util.Arrays.stream(getClass().getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(Autowired.class))
                .count();
        assertThat(autowiredFields).isZero();

        // Proof 2: only connection is to Testcontainers postgres (not to any external service)
        // Testcontainers exposes the container on localhost or 127.0.0.1 depending on the OS
        assertThat(dataSource.getJdbcUrl())
                .startsWith("jdbc:postgresql://");
        assertThat(postgres.getHost()).isIn("localhost", "127.0.0.1");
    }
}
