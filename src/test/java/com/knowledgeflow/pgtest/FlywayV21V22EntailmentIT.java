package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-010-A, A3: V21, V22 (iguais ao piloto) e V25 aplicam-se em PostgreSQL real e protegem os registos da barreira.
 * Com referências válidas, cada protecção é exercida isoladamente:
 * <ul>
 *   <li>DEMONSTRATED exige SUPORTA <b>não nulo</b> e citação verificada (V22 + V25; V22 sozinha aceitava NULL);</li>
 *   <li>cada chave estrangeira (investigação, proposição, evidência e o passo opcional do modelo) é verificada só, com as
 *       restantes válidas;</li>
 *   <li>as CHECK dos passos (V21/V22) admitem as fases, origens e resultados novos.</li>
 * </ul>
 * Run: mvn verify -Ppgtest -Dit.test=FlywayV21V22EntailmentIT
 */
@Testcontainers
class FlywayV21V22EntailmentIT {

    @Container
    static final PostgreSQLContainer<?> postgres = PgContainers.pgvector();

    static final UUID ORG = UUID.randomUUID(), INV = UUID.randomUUID(), STEP = UUID.randomUUID(),
            EV = UUID.randomUUID(), PROP = UUID.randomUUID();

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void exec(String sql, Object... args) {
        try (Connection c = connection(); var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static String sqlState(Runnable r) {
        try {
            r.run();
            return "aceite";
        } catch (RuntimeException e) {
            Throwable t = e;
            while (t != null && !(t instanceof SQLException)) t = t.getCause();
            return t == null ? e.toString() : ((SQLException) t).getSQLState();
        }
    }

    private static final String INSERT = "INSERT INTO research_entailment_checks (id, investigation_id, proposition_id,"
            + " evidence_id, model_step_id, verdict, citation_verified, decision, decision_reason, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'r', now())";

    private static String insert(UUID inv, UUID prop, UUID ev, UUID step, String verdict, boolean cited, String decision) {
        return sqlState(() -> exec(INSERT, UUID.randomUUID(), inv, prop, ev, step, verdict, cited, decision));
    }

    @BeforeAll
    static void migrateAndSeed() {
        var result = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        assertThat(result.targetSchemaVersion).isEqualTo("25");
        exec("INSERT INTO organizations(id, name, tax_identifier, created_at, updated_at) VALUES (?,?,?,NOW(),NOW())",
                ORG, "Org V22", "PT922000001");
        exec("INSERT INTO research_investigations(id, organization_id, question, trigger_reason, status, created_at)"
                + " VALUES (?,?,?,'NO_RELEVANT_QA','OPEN',NOW())", INV, ORG, "Pergunta?");
        exec("INSERT INTO research_steps(id, investigation_id, step_order, phase, origin, outcome, occurred_at,"
                + " model_provider, model_name) VALUES (?,?,1,'ENTAILMENT','MODEL','MODEL_FAILED',NOW(),'stub','stub-v1')",
                STEP, INV);
        exec("INSERT INTO research_evidence(id, organization_id, investigation_id, consultation_step_id, source_type,"
                + " authority, reference_key, legal_reference, title, url, retrieved_at, content_sha256, content,"
                + " validity_status, validity_reason, classification, classification_reason, created_at)"
                + " VALUES (?,?,?,?,'LEGISLATION','AT_CONSOLIDATED_CODE','CIRS:78','artigo 78.º do CIRS','Artigo 78.º',"
                + " 'https://info.portaldasfinancas.gov.pt/x',NOW(),?,'texto','CONSOLIDATED_AT_RETRIEVAL','r','ACCEPTED','r',NOW())",
                EV, ORG, INV, STEP, "0".repeat(64));
        exec("INSERT INTO knowledge_propositions(id, organization_id, investigation_id, kind, statement, foundation,"
                + " validity_status, confidence, status, status_reason, created_at, updated_at)"
                + " VALUES (?,?,?,'RULE','s','artigo 78.º do CIRS','CONSOLIDATED_AT_RETRIEVAL','ANCHORED','CANDIDATE','r',NOW(),NOW())",
                PROP, ORG, INV);
    }

    @Test
    void validRecordsAreAccepted() {
        assertThat(insert(INV, PROP, EV, STEP, "SUPORTA", true, "DEMONSTRATED")).isEqualTo("aceite");
        assertThat(insert(INV, PROP, EV, null, null, false, "NOT_DEMONSTRATED")).isEqualTo("aceite");
    }

    @Test
    void demonstratedRequiresANonNullSupportingVerdictAndAVerifiedCitation() {
        assertThat(insert(INV, PROP, EV, null, null, true, "DEMONSTRATED")).as("verdict NULL (lacuna da V22)").isEqualTo("23514");
        assertThat(insert(INV, PROP, EV, null, "NAO_SUPORTA", true, "DEMONSTRATED")).isEqualTo("23514");
        assertThat(insert(INV, PROP, EV, null, "SUPORTA", false, "DEMONSTRATED")).isEqualTo("23514");
        assertThat(insert(INV, PROP, EV, null, "SUPORTA", true, "TALVEZ")).isEqualTo("23514");
    }

    @Test
    void eachForeignKeyIsEnforcedOnItsOwn() {
        assertThat(insert(UUID.randomUUID(), PROP, EV, null, null, false, "NOT_DEMONSTRATED")).as("investigação").isEqualTo("23503");
        assertThat(insert(INV, UUID.randomUUID(), EV, null, null, false, "NOT_DEMONSTRATED")).as("proposição").isEqualTo("23503");
        assertThat(insert(INV, PROP, UUID.randomUUID(), null, null, false, "NOT_DEMONSTRATED")).as("evidência").isEqualTo("23503");
        assertThat(insert(INV, PROP, EV, UUID.randomUUID(), null, false, "NOT_DEMONSTRATED")).as("passo do modelo").isEqualTo("23503");
    }

    @Test
    void stepConstraintsAdmitTheNewValues() throws Exception {
        try (Connection conn = connection(); var st = conn.createStatement();
             var rs = st.executeQuery("SELECT string_agg(pg_get_constraintdef(oid), ' ') FROM pg_constraint"
                     + " WHERE conrelid = 'research_steps'::regclass AND contype = 'c'")) {
            rs.next();
            assertThat(rs.getString(1)).contains("ENTAILMENT", "STRUCTURING", "ANSWER_COMPOSITION", "MODEL",
                    "VERIFICATION", "MODEL_COMPLETED", "MODEL_UNPARSEABLE", "MODEL_FAILED");
        }
    }
}
