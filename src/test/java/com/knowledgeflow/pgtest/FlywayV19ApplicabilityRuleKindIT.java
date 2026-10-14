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
 * V18 → V19 (C9-A, ADR-009) em PostgreSQL real, descartável: a coluna {@code kind} é aditiva, as
 * exclusões existentes ficam {@code EXCLUDES} (comportamento inalterado), o CHECK só admite
 * {@code EXCLUDES}/{@code REQUIRES} e o UNIQUE(qa, marker) mantém-se para os dois tipos.
 * Run: mvn verify -Ppgtest -Dit.test=FlywayV19ApplicabilityRuleKindIT
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlywayV19ApplicabilityRuleKindIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final UUID ORG_ID = UUID.fromString("a1900000-0000-0000-0000-000000000001");
    private static final UUID QA_ID = UUID.fromString("a1900000-0000-0000-0000-000000000002");

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
    @DisplayName("V1→V18 com uma Q&A publicada e uma exclusão existente")
    void migrateToV18_withExistingExclusion() {
        assertThat(flyway("18").migrate().migrationsExecuted).isEqualTo(18);
        jdbc.update("INSERT INTO organizations(id, name, tax_identifier, created_at, updated_at) VALUES (?,?,?,NOW(),NOW())",
                ORG_ID, "Org V19", "PT919000001");
        jdbc.update("INSERT INTO knowledge_question_answers(id, organization_id, original_question, original_answer,"
                        + " curation_status, published_at, published_by, created_at, updated_at, version)"
                        + " VALUES (?,?,?,?,'VALIDATED',NOW(),'Publicador',NOW(),NOW(),0)",
                QA_ID, ORG_ID, "Que despesas posso deduzir às rendas?", "Resposta.");
        jdbc.update("INSERT INTO knowledge_qa_applicability_exclusions(id, knowledge_qa_id, marker, created_at)"
                + " VALUES (?,?,?,NOW())", UUID.randomUUID(), QA_ID, "INQUILINO");
    }

    @Test
    @Order(2)
    @DisplayName("V19 aplica-se; a exclusão existente fica EXCLUDES")
    void migrateToV19_existingRowsAreExclusions() {
        assertThat(flyway("19").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("19").info().current().getVersion().getVersion()).isEqualTo("19");
        assertThat(jdbc.queryForObject("SELECT kind FROM knowledge_qa_applicability_exclusions WHERE marker = 'INQUILINO'",
                String.class)).isEqualTo("EXCLUDES");
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns"
                + " WHERE table_name = 'knowledge_qa_applicability_exclusions' AND column_name = 'kind'", String.class))
                .isEqualTo("NO");
    }

    @Test
    @Order(3)
    @DisplayName("CHECK: REQUIRES aceite, tipo desconhecido recusado; UNIQUE(qa, marker) vale para os dois tipos")
    void kindCheckAndUniqueness() {
        insertRule("ACTO_ISOLADO", "REQUIRES");
        assertThatThrownBy(() -> insertRule("SUBLOCACAO", "PREFERS")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRule("INQUILINO", "REQUIRES")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_qa_applicability_exclusions WHERE knowledge_qa_id = ?",
                Integer.class, QA_ID)).isEqualTo(2);
    }

    @Test
    @Order(4)
    @DisplayName("Flyway validate passa no schema final")
    void validate() {
        flyway("19").validate();
    }

    private void insertRule(String marker, String kind) {
        jdbc.update("INSERT INTO knowledge_qa_applicability_exclusions(id, knowledge_qa_id, marker, kind, created_at)"
                + " VALUES (?,?,?,?,NOW())", UUID.randomUUID(), QA_ID, marker, kind);
    }
}
