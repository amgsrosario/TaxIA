package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V15 → V16 (M4-SCOPE-V2) em PostgreSQL real, descartável: aditiva, preserva Q&amp;A legacy
 * (revisão a NULL), UNIQUE(qa, marker), ON DELETE CASCADE e sem CHECK de vocabulário na BD.
 * Run: mvn verify -Ppgtest -Dit.test=FlywayV16ApplicabilityExclusionsIT
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlywayV16ApplicabilityExclusionsIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final UUID ORG_ID = UUID.fromString("a1600000-0000-0000-0000-000000000001");
    private static final UUID QA_ID = UUID.fromString("a1600000-0000-0000-0000-000000000002");

    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeAll
    void init() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(3);
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    void close() {
        if (dataSource != null) dataSource.close();
    }

    private Flyway flyway(String target) {
        var fb = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
        if (target != null) fb = fb.target(target);
        return fb.load();
    }

    @Test
    @Order(1)
    @DisplayName("V1→V15 e uma Q&A legacy publicada")
    void migrateToV15_withLegacyQa() {
        assertThat(flyway("15").migrate().migrationsExecuted).isEqualTo(15);
        jdbc.update("INSERT INTO organizations(id, name, tax_identifier, created_at, updated_at) VALUES (?,?,?,NOW(),NOW())",
                ORG_ID, "Org V16", "PT916000001");
        jdbc.update("INSERT INTO knowledge_question_answers(id, organization_id, original_question, original_answer,"
                        + " curation_status, published_at, published_by, created_at, updated_at, version)"
                        + " VALUES (?,?,?,?,'VALIDATED',NOW(),'Publicador',NOW(),NOW(),0)",
                QA_ID, ORG_ID, "Pergunta legacy?", "Resposta legacy.");
    }

    @Test
    @Order(2)
    @DisplayName("V16 aplica-se e preserva a Q&A legacy, com revisão de aplicabilidade a NULL")
    void migrateToV16_isAdditive() {
        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway(null).info().current().getVersion().getVersion()).isEqualTo("16");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_question_answers WHERE id = ?"
                + " AND applicability_reviewed_at IS NULL AND applicability_reviewed_by IS NULL"
                + " AND published_at IS NOT NULL", Integer.class, QA_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_qa_applicability_exclusions", Integer.class))
                .isZero();
    }

    @Test
    @Order(3)
    @DisplayName("UNIQUE(qa, marker); marcador fora do vocabulário é aceite pela BD (validação é na aplicação)")
    void uniqueAndNoVocabularyCheck() {
        insertExclusion("INQUILINO");
        assertThatThrownBy(() -> insertExclusion("INQUILINO")).isInstanceOf(DataIntegrityViolationException.class);
        insertExclusion("CODIGO_FUTURO");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_qa_applicability_exclusions WHERE knowledge_qa_id = ?",
                Integer.class, QA_ID)).isEqualTo(2);
    }

    @Test
    @Order(4)
    @DisplayName("Apagar a Q&A apaga as exclusões (ON DELETE CASCADE)")
    void cascadeDelete() {
        jdbc.update("DELETE FROM knowledge_question_answers WHERE id = ?", QA_ID);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_qa_applicability_exclusions", Integer.class))
                .isZero();
    }

    @Test
    @Order(5)
    @DisplayName("Flyway validate passa no schema final")
    void validate() {
        flyway(null).validate();
    }

    private void insertExclusion(String marker) {
        jdbc.update("INSERT INTO knowledge_qa_applicability_exclusions(id, knowledge_qa_id, marker, created_at)"
                + " VALUES (?,?,?,NOW())", UUID.randomUUID(), QA_ID, marker);
    }
}
