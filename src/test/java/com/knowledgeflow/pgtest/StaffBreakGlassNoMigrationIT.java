package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import com.knowledgeflow.users.credentials.StaffAdminRecoveryLauncherTestBridge;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-006 break-glass never migrates: the launcher's own pilot context, booted against a
 * disposable PostgreSQL <i>named</i> knowledgeflow_pilot at V16, fails Hibernate validation and
 * leaves the schema at V16 (no V17 side effect). At V17 it boots and still migrates nothing.
 *
 * Run: mvn verify -Ppgtest -Dit.test=StaffBreakGlassNoMigrationIT
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StaffBreakGlassNoMigrationIT {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16").withDatabaseName("knowledgeflow_pilot");

    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeAll
    void init() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(2);
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    void close() {
        if (dataSource != null) dataSource.close();
    }

    private String[] args() {
        return new String[] {
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--knowledgeflow.ai.primary-provider=stub",
                "--knowledgeflow.ai.providers.stub.enabled=true",
                "--knowledgeflow.security.jwt.secret=pilot-it-only-fictitious-jwt-signing-key-0123456789"
        };
    }

    private int schemaVersion() {
        return jdbc.queryForObject("SELECT MAX(version::int) FROM flyway_schema_history WHERE success", Integer.class);
    }

    @Test
    @DisplayName("schema em V16: o contexto do launcher falha na validação e não aplica a V17")
    void outdatedSchemaIsNeverMigrated() {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("16").load().migrate();
        assertThat(schemaVersion()).isEqualTo(16);

        assertThatThrownBy(() -> StaffAdminRecoveryLauncherTestBridge.boot(args()).close())
                .isInstanceOf(Exception.class);

        assertThat(schemaVersion()).isEqualTo(16);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns"
                + " WHERE table_name = 'users' AND column_name = 'token_version'", Integer.class)).isZero();

        // Current schema: boots, and still no migration is recorded by the launcher.
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        int rows = jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class);
        try (ConfigurableApplicationContext ctx = StaffAdminRecoveryLauncherTestBridge.boot(args())) {
            assertThat(ctx.getEnvironment().getProperty("spring.flyway.enabled")).isEqualTo("false");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class)).isEqualTo(rows);
    }
}
