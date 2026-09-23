package com.knowledgeflow.config.pilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Isolated proof of {@link PilotDatasourceGuard} — Bloco E, E9C-pilot-profile (PROMPT 90).
 *
 * <p>Each case spins an ephemeral Testcontainers PostgreSQL with a chosen database name and
 * runs the guard's read-only validation against it. The real {@code knowledgeflow_pilot}
 * database is never touched; no data is ever written.
 *
 * <ul>
 *   <li>A — database {@code knowledgeflow_pilot}: guard accepts (no exception).</li>
 *   <li>B — database {@code knowledgeflow}: guard fails closed (dev database refused).</li>
 *   <li>C — database with an unexpected name: guard fails closed.</li>
 * </ul>
 */
class PilotDatasourceGuardIT {

    private static PostgreSQLContainer<?> container(String databaseName) {
        return new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                .withDatabaseName(databaseName)
                .withUsername("guard_it")
                .withPassword("guard_it");
    }

    private static DriverManagerDataSource dataSourceFor(PostgreSQLContainer<?> pg) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(pg.getDriverClassName());
        ds.setUrl(pg.getJdbcUrl());
        ds.setUsername(pg.getUsername());
        ds.setPassword(pg.getPassword());
        return ds;
    }

    @Test
    @DisplayName("A: guard accepts database knowledgeflow_pilot")
    void acceptsPilotDatabase() {
        try (PostgreSQLContainer<?> pg = container("knowledgeflow_pilot")) {
            pg.start();
            // No exception == accepted.
            PilotDatasourceGuard.validate(dataSourceFor(pg));
        }
    }

    @Test
    @DisplayName("B: guard fails closed against the dev database knowledgeflow")
    void rejectsDevDatabase() {
        try (PostgreSQLContainer<?> pg = container("knowledgeflow")) {
            pg.start();
            assertThatThrownBy(() -> PilotDatasourceGuard.validate(dataSourceFor(pg)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("knowledgeflow")
                    .hasMessageContaining("refusing to start");
        }
    }

    @Test
    @DisplayName("C: guard fails closed against an unexpected database name")
    void rejectsUnexpectedDatabase() {
        try (PostgreSQLContainer<?> pg = container("knowledgeflow_staging")) {
            pg.start();
            assertThatThrownBy(() -> PilotDatasourceGuard.validate(dataSourceFor(pg)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("knowledgeflow_pilot");
        }
    }

    @Test
    @DisplayName("host extraction: parses loopback host, tolerates malformed url")
    void extractsHost() {
        assertThat(PilotDatasourceGuard.extractHost(
                "jdbc:postgresql://localhost:15432/knowledgeflow_pilot")).isEqualTo("localhost");
        assertThat(PilotDatasourceGuard.extractHost(
                "jdbc:postgresql://127.0.0.1:15432/knowledgeflow_pilot")).isEqualTo("127.0.0.1");
        assertThat(PilotDatasourceGuard.extractHost(null)).isNull();
        assertThat(PilotDatasourceGuard.extractHost("not-a-jdbc-url")).isNull();
    }
}
