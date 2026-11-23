package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-010-A, A3: as migrações V21 e V22 (iguais ao piloto) aplicam-se sobre V20 em PostgreSQL real e criam as
 * protecções da barreira: a CHECK {@code ck_rec_demonstrated} (DEMONSTRATED exige SUPORTA e citação verificada) e as
 * chaves estrangeiras de {@code research_entailment_checks}; V21 alarga as CHECK dos passos às fases e resultados novos.
 * Run: mvn verify -Ppgtest -Dit.test=FlywayV21V22EntailmentIT
 */
@Testcontainers
class FlywayV21V22EntailmentIT {

    @Container
    static final PostgreSQLContainer<?> postgres = PgContainers.pgvector();

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static String sqlState(Runnable r) {
        try {
            r.run();
            return null;
        } catch (RuntimeException e) {
            Throwable t = e;
            while (t != null && !(t instanceof SQLException)) t = t.getCause();
            return t == null ? e.toString() : ((SQLException) t).getSQLState();
        }
    }

    private static void exec(String sql, Object... args) {
        try (Connection c = connection(); var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static final String INSERT = "INSERT INTO research_entailment_checks (id, investigation_id, proposition_id,"
            + " evidence_id, model_step_id, verdict, citation_verified, decision, decision_reason, created_at)"
            + " VALUES (?, ?, ?, ?, NULL, ?, ?, ?, 'r', now())";

    @Test
    void v21AndV22ApplyAndProtectTheBarrierRecords() throws Exception {
        var result = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        assertThat(result.targetSchemaVersion).isEqualTo("22");

        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        // CHECK: DEMONSTRATED sem SUPORTA, ou sem citação verificada, é recusado pela base (23514).
        assertThat(sqlState(() -> exec(INSERT, UUID.randomUUID(), a, b, c, "NAO_SUPORTA", true, "DEMONSTRATED")))
                .isEqualTo("23514");
        assertThat(sqlState(() -> exec(INSERT, UUID.randomUUID(), a, b, c, "SUPORTA", false, "DEMONSTRATED")))
                .isEqualTo("23514");
        assertThat(sqlState(() -> exec(INSERT, UUID.randomUUID(), a, b, c, "SUPORTA", true, "TALVEZ")))
                .isEqualTo("23514");
        // Registo coerente mas sem investigação, proposição ou evidência existentes: chave estrangeira (23503).
        assertThat(sqlState(() -> exec(INSERT, UUID.randomUUID(), a, b, c, null, false, "NOT_DEMONSTRATED")))
                .isEqualTo("23503");

        // V21/V22: as CHECK dos passos aceitam as fases e origens novas (texto das constraints).
        try (Connection conn = connection(); var st = conn.createStatement();
             var rs = st.executeQuery("SELECT string_agg(pg_get_constraintdef(oid), ' ') FROM pg_constraint"
                     + " WHERE conrelid = 'research_steps'::regclass AND contype = 'c'")) {
            rs.next();
            String defs = rs.getString(1);
            assertThat(defs).contains("ENTAILMENT", "STRUCTURING", "ANSWER_COMPOSITION", "MODEL", "VERIFICATION",
                    "MODEL_COMPLETED", "MODEL_UNPARSEABLE", "MODEL_FAILED");
        }
    }
}
