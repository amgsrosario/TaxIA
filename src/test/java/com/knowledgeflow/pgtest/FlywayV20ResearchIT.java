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
 * V19 → V20 (KF-M01, ADR-010) em PostgreSQL real, descartável: a migração é aditiva (o corpus
 * existente fica intacto) e as restrições da base impedem promoção (evidência só NOT_PROMOTED,
 * proposições sem estado promovido) e respostas acima do mínimo do Aditamento §2.
 * Run: mvn verify -Ppgtest -Dit.test=FlywayV20ResearchIT
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlywayV20ResearchIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final UUID ORG_ID = UUID.fromString("a2000000-0000-0000-0000-000000000001");
    private static final UUID QA_ID = UUID.fromString("a2000000-0000-0000-0000-000000000002");
    private static final UUID INV_ID = UUID.fromString("a2000000-0000-0000-0000-000000000003");
    private static final UUID STEP_ID = UUID.fromString("a2000000-0000-0000-0000-000000000004");
    private static final UUID EV_ID = UUID.fromString("a2000000-0000-0000-0000-000000000005");
    private static final UUID PROP_ID = UUID.fromString("a2000000-0000-0000-0000-000000000006");

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
    @DisplayName("V1→V19 com uma Q&A publicada; V20 aplica-se e não toca no corpus")
    void migrationIsAdditive() {
        assertThat(flyway("19").migrate().migrationsExecuted).isEqualTo(19);
        jdbc.update("INSERT INTO organizations(id, name, tax_identifier, created_at, updated_at) VALUES (?,?,?,NOW(),NOW())",
                ORG_ID, "Org V20", "PT920000001");
        jdbc.update("INSERT INTO knowledge_question_answers(id, organization_id, original_question, original_answer,"
                        + " curation_status, published_at, published_by, created_at, updated_at, version)"
                        + " VALUES (?,?,?,?,'VALIDATED',NOW(),'Publicador',NOW(),NOW(),3)",
                QA_ID, ORG_ID, "Pergunta publicada?", "Resposta publicada.");

        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class))
                .isEqualTo("20");
        assertThat(jdbc.queryForObject("SELECT curation_status || ':' || version FROM knowledge_question_answers WHERE id = ?",
                String.class, QA_ID)).isEqualTo("VALIDATED:3");
        for (String table : new String[] {"research_investigations", "research_steps", "research_evidence",
                "knowledge_propositions", "knowledge_proposition_supports", "knowledge_proposition_conditions",
                "knowledge_proposition_relations", "critical_memory_entries", "critical_memory_history"}) {
            assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table)).as(table).isTrue();
        }
    }

    @Test
    @Order(2)
    @DisplayName("Investigação só admite COM_LIMITACOES/RESPOSTA_LIMITE e parecer SUGGESTED/REQUIRED")
    void investigationAnswerFloor() {
        jdbc.update("INSERT INTO research_investigations(id, organization_id, question, trigger_reason, status,"
                + " answer_type, parecer_requirement, created_at) VALUES (?,?,?,'NO_RELEVANT_QA','COMPLETED',"
                + " 'CONSULTA_DOCUMENTADA_COM_LIMITACOES','SUGGESTED',NOW())", INV_ID, ORG_ID, "Pergunta inédita?");

        assertThatThrownBy(() -> jdbc.update("UPDATE research_investigations SET answer_type='CONSULTA_DOCUMENTADA' WHERE id=?", INV_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE research_investigations SET parecer_requirement='NONE' WHERE id=?", INV_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(3)
    @DisplayName("Evidência só NOT_PROMOTED; proposição sem estado promovido")
    void noPromotionAtDatabaseLevel() {
        jdbc.update("INSERT INTO research_steps(id, investigation_id, step_order, phase, origin, outcome, occurred_at)"
                + " VALUES (?,?,1,'CONSULTATION','OFFICIAL_SOURCE','FETCHED',NOW())", STEP_ID, INV_ID);
        jdbc.update("INSERT INTO research_evidence(id, organization_id, investigation_id, consultation_step_id, source_type,"
                        + " authority, reference_key, legal_reference, title, url, retrieved_at, content_sha256, content,"
                        + " validity_status, validity_reason, classification, classification_reason, created_at)"
                        + " VALUES (?,?,?,?,'LEGISLATION','AT_CONSOLIDATED_CODE','CIRS:78','artigo 78.º do CIRS','Artigo 78.º',"
                        + " 'https://info.portaldasfinancas.gov.pt/x',NOW(),?,'texto','CONSOLIDATED_AT_RETRIEVAL','r','ACCEPTED','r',NOW())",
                EV_ID, ORG_ID, INV_ID, STEP_ID, "0".repeat(64));
        assertThat(jdbc.queryForObject("SELECT promotion_status FROM research_evidence WHERE id=?", String.class, EV_ID))
                .isEqualTo("NOT_PROMOTED");
        assertThatThrownBy(() -> jdbc.update("UPDATE research_evidence SET promotion_status='PROMOTED' WHERE id=?", EV_ID))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("INSERT INTO knowledge_propositions(id, organization_id, investigation_id, kind, statement, foundation,"
                + " validity_status, confidence, status, status_reason, created_at, updated_at)"
                + " VALUES (?,?,?,'RULE','s','artigo 78.º do CIRS','CONSOLIDATED_AT_RETRIEVAL','ANCHORED','CANDIDATE','r',NOW(),NOW())",
                PROP_ID, ORG_ID, INV_ID);
        for (String status : new String[] {"PROMOTED", "VALIDATED", "PUBLISHED"}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_propositions SET status=? WHERE id=?", status, PROP_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("INSERT INTO knowledge_proposition_relations(id, from_proposition_id,"
                + " to_proposition_id, relation, created_at) VALUES (?,?,?,'CONFLICTS_WITH',NOW())",
                UUID.randomUUID(), PROP_ID, PROP_ID)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(4)
    @DisplayName("Memória crítica: classificação controlada e uma só entrada por afirmação/classificação")
    void criticalMemoryConstraints() {
        String insert = "INSERT INTO critical_memory_entries(id, organization_id, claim, claim_sha256, classification,"
                + " reason, provenance, observed_at, status, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,'r','p',NOW(),'ACTIVE',NOW(),NOW())";
        jdbc.update(insert, UUID.randomUUID(), ORG_ID, "afirmação", "a".repeat(64), "REJECTED");
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), ORG_ID, "afirmação", "a".repeat(64), "REJECTED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, UUID.randomUUID(), ORG_ID, "afirmação", "a".repeat(64), "CONTRADICTORY");
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), ORG_ID, "x", "b".repeat(64), "VALID"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
