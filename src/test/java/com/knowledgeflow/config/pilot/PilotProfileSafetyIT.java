package com.knowledgeflow.config.pilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ingestion.atfaq.AtFaqProperties;
import com.knowledgeflow.ingestion.atfaq.batch.GovernedPilotServiceActors;
import com.knowledgeflow.rag.EmbeddingClient;
import com.knowledgeflow.rag.StubEmbeddingService;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Holistic proof that the <b>pilot</b> profile boots safely against a pilot-named database and
 * performs no writes at startup — Bloco E, E9C-pilot-profile (PROMPT 90).
 *
 * <p>The datasource is an ephemeral Testcontainers PostgreSQL whose database is
 * {@code knowledgeflow_pilot} (so {@link PilotDatasourceGuard} accepts the accept-path) — it is
 * NOT the real {@code knowledgeflow_pilot} database. A dummy Anthropic key is supplied purely so
 * the context can construct; no AI or embedding network call happens at startup.
 *
 * <p>Proves, at startup, in the pilot profile:
 * <ul>
 *   <li>the context boots (guard accept-path);</li>
 *   <li>real embeddings are wired: {@link EmbeddingClient} present, {@link StubEmbeddingService}
 *       absent (PASSO 6);</li>
 *   <li>zero auto-execution: no {@link CommandLineRunner} / {@link ApplicationRunner} beans
 *       (PASSO 8/13);</li>
 *   <li>the E9C master switch is off (PASSO 7);</li>
 *   <li>no data is written at startup: {@code users}, {@code knowledge_question_answers} and
 *       {@code knowledge_qa_embeddings} are empty, and neither governed pilot actor row exists —
 *       i.e. no actor provisioning, publish, index or rollback (PASSO 10, F, G).</li>
 * </ul>
 */
@SpringBootTest(properties = {
        // PASSO 11-C — synthetic DB credentials satisfy the mandatory (no-default)
        // placeholders in application-pilot.yml so the context can bind. The actual
        // connection still comes from @ServiceConnection (the Testcontainers container).
        "spring.datasource.username=pilot_it_synthetic_user",
        "spring.datasource.password=pilot_it_synthetic_password",
        // PASSO 10 — the pilot profile makes NO silent AI decision, so an explicit
        // decision must be supplied to boot. Use the local stub (no external call,
        // no dummy Anthropic key). This proves "with an explicit decision, it boots".
        "knowledgeflow.ai.primary-provider=stub",
        "knowledgeflow.ai.providers.stub.enabled=true"
})
@ActiveProfiles("pilot")
@Testcontainers
class PilotProfileSafetyIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("knowledgeflow_pilot");

    @Autowired ApplicationContext context;
    @Autowired AtFaqProperties atFaqProperties;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("pilot boots with real embeddings, no runners, E9C off, and zero startup writes")
    void pilotProfileBootsSafely() {
        // The database really is the pilot database (guard accept-path exercised for real).
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        assertThat(database).isEqualTo("knowledgeflow_pilot");

        // PASSO 6 — real embeddings, never a stub.
        assertThat(context.getBeansOfType(EmbeddingClient.class)).isNotEmpty();
        assertThat(context.getBeansOfType(StubEmbeddingService.class)).isEmpty();

        // PASSO 8 / 13 — zero auto-execution: no runners of any kind.
        Map<String, CommandLineRunner> clr = context.getBeansOfType(CommandLineRunner.class);
        Map<String, ApplicationRunner> ar = context.getBeansOfType(ApplicationRunner.class);
        assertThat(clr).as("no CommandLineRunner beans in pilot").isEmpty();
        assertThat(ar).as("no ApplicationRunner beans in pilot").isEmpty();

        // PASSO 7 — governed E9C master switch off by default.
        assertThat(atFaqProperties.isE9cPilotEnabled()).isFalse();
        assertThat(atFaqProperties.isEnabled()).isFalse();

        // PASSO 10 / F / G — nothing written at startup.
        assertThat(count("users")).isZero();
        assertThat(count("knowledge_question_answers")).isZero();
        assertThat(count("knowledge_qa_embeddings")).isZero();

        // PASSO 10 — governed pilot actors were NOT auto-provisioned.
        assertThat(userExists(GovernedPilotServiceActors.publisherActorId().toString())).isFalse();
        assertThat(userExists(GovernedPilotServiceActors.rollbackActorId().toString())).isFalse();
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return n != null ? n : 0L;
    }

    private boolean userExists(String id) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?::uuid", Long.class, id);
        return n != null && n > 0;
    }
}
