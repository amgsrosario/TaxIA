package com.knowledgeflow.ingestion.atfaq.pilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ingestion.atfaq.batch.GovernedPilotServiceActors;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerCurationService;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerImportService;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.users.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the guarded one-shot pilot <b>operations</b> tooling end-to-end in an <b>isolated</b>
 * database — Bloco E, E9C-pilot-operations (PROMPT 100).
 *
 * <p>Frase-mestra: "Preparar o primeiro caso real é uma sequência de gestos deliberados, cada um
 * sobre um único alvo explícito, reutilizando exclusivamente os serviços governados existentes."
 *
 * <p>The database is an ephemeral Testcontainers pgvector instance <em>named</em>
 * {@code knowledgeflow_pilot} purely so the reused base gate
 * ({@link com.knowledgeflow.config.pilot.PilotDatasourceGuard#validate}) exercises its accept-path —
 * it is <b>NOT</b> the real pilot database, and the fixture key {@code PILOT-OPS-CURATED-FIX-001} is
 * never the real frozen candidate. The tooling is wired with the real governed import and curation
 * services, so the full lifecycle is genuine.
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@code provision-actors} inserts the two non-login actors, then is idempotent (NO_CHANGE);</li>
 *   <li>the full preparation lifecycle: import-one (IMPORTED) → curate-one (short + technical answer)
 *       → add-source-one (LEGISLATION) → pending-review-one (PENDING_REVIEW) → validate-one
 *       (VALIDATED), leaving the candidate <b>VALIDATED, unpublished, RAG-eligible</b> and audited
 *       under the human curator;</li>
 *   <li>idempotency: a repeated import / pending-review / validate is NO_CHANGE and never duplicates;</li>
 *   <li>invalid states fail closed: validate before pending, validate with no source, pending on a
 *       VALIDATED entry, a missing key and a malformed source system are all BLOCKED;</li>
 *   <li>the hard N=1 limit: an ambiguous key (&gt;1 match) is refused, never acted on;</li>
 *   <li>the base gate blocks a forbidden (non-pilot) database.</li>
 * </ul>
 *
 * <p>Run: mvn verify -Ppgtest -Dit.test=TaxiaPilotOperationsIT
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TaxiaPilotOperationsIT {

    private static final String SOURCE_CURATED = "taxia-curated";
    private static final String FIX = "PILOT-OPS-CURATED-FIX-001";
    private static final String MISSING_KEY = "PILOT-OPS-DOES-NOT-EXIST";
    private static final String NO_SOURCE_KEY = "PILOT-OPS-NO-SOURCE-014";
    private static final String IMPORTED_ONLY_KEY = "PILOT-OPS-IMPORTED-ONLY-015";
    private static final String AMBIGUOUS_KEY = "PILOT-OPS-AMBIGUOUS-777";
    private static final String REVIEWER = "Revisor Piloto";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("knowledgeflow_pilot");

    @Autowired KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired KnowledgeQuestionAnswerImportService importService;
    @Autowired KnowledgeQuestionAnswerCurationService curationService;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private TaxiaPilotOperations ops;
    private Organization org;
    private UUID curatorId;

    @BeforeAll
    void setUp() {
        org = organizationRepository.save(new Organization("Org — Pilot Operations E9C", null));
        curatorId = insertPilotAdmin();
        ops = new TaxiaPilotOperations(
                qaRepository, importService, curationService,
                organizationRepository, userRepository, dataSource, jdbc);
    }

    // =========================================================================
    // provision-actors
    // =========================================================================

    @Test @Order(1)
    @DisplayName("provision-actors: PROVISIONED (2 rows), then NO_CHANGE (idempotent)")
    void provisionActorsThenIdempotent() {
        PilotOpsResult first = ops.provisionActors();
        assertThat(first.outcome()).isEqualTo(PilotOpsOutcome.PROVISIONED);
        assertThat(first.wroteChange()).isTrue();
        assertThat(first.render()).endsWith("RESULT=PROVISIONED");
        assertThat(userExists(GovernedPilotServiceActors.publisherActorId())).isTrue();
        assertThat(userExists(GovernedPilotServiceActors.rollbackActorId())).isTrue();

        PilotOpsResult again = ops.provisionActors();
        assertThat(again.outcome()).isEqualTo(PilotOpsOutcome.NO_CHANGE);
        assertThat(again.wroteChange()).isFalse();
        assertThat(again.render()).endsWith("RESULT=NO_CHANGE");
    }

    // =========================================================================
    // full preparation lifecycle
    // =========================================================================

    @Test @Order(2)
    @DisplayName("lifecycle: import → curate → add-source → pending-review → validate leaves VALIDATED, unpublished, RAG-eligible")
    void fullPreparationLifecycle() {
        // import-one → IMPORTED (exactly one candidate).
        PilotOpsResult imp = ops.importOne(
                SOURCE_CURATED, FIX,
                "Durante quanto tempo deve conservar os registos de IVA (fixture)?",
                "Resposta importada em bruto (fixture).");
        assertThat(imp.outcome()).isEqualTo(PilotOpsOutcome.IMPORTED);
        assertThat(imp.wroteChange()).isTrue();
        UUID id = imp.targetQaId();
        assertThat(id).isNotNull();
        assertThat(qaRepository.findById(id).orElseThrow().getCurationStatus())
                .isEqualTo(KnowledgeCurationStatus.IMPORTED);
        assertThat(auditCount(id, "KNOWLEDGE_QA_IMPORTED", curatorId)).isEqualTo(1);

        // curate-one → sets the curated short + technical answer and classification (merge).
        PilotOpsResult cur = ops.curateOne(
                SOURCE_CURATED, FIX,
                "Resposta curta curada (fixture).",
                "Resposta técnica completa curada para o piloto (fixture).",
                "Resposta normalizada (fixture)?",
                KnowledgeTopic.IVA, null, "PT",
                KnowledgeRiskLevel.LOW, false, null);
        assertThat(cur.outcome()).isEqualTo(PilotOpsOutcome.CURATED);
        assertThat(cur.wroteChange()).isTrue();
        KnowledgeQuestionAnswer curated = qaRepository.findById(id).orElseThrow();
        assertThat(curated.getShortAnswer()).isNotBlank();
        assertThat(curated.getTechnicalAnswer()).isNotBlank();
        assertThat(curated.getTopic()).isEqualTo(KnowledgeTopic.IVA);
        assertThat(curated.getRiskLevel()).isEqualTo(KnowledgeRiskLevel.LOW);
        assertThat(auditCount(id, "KNOWLEDGE_QA_UPDATED", curatorId)).isEqualTo(1);

        // add-source-one → one LEGISLATION source.
        PilotOpsResult src = ops.addSourceOne(
                SOURCE_CURATED, FIX,
                KnowledgeSourceType.LEGISLATION, "CIVA", "Artigo 52.º, n.º 1, do CIVA", null, null);
        assertThat(src.outcome()).isEqualTo(PilotOpsOutcome.SOURCE_ADDED);
        assertThat(src.wroteChange()).isTrue();
        assertThat(sourceCount(id)).isEqualTo(1);
        assertThat(auditCount(id, "KNOWLEDGE_QA_SOURCE_ADDED", curatorId)).isEqualTo(1);

        // pending-review-one → PENDING_REVIEW.
        PilotOpsResult pend = ops.pendingReviewOne(SOURCE_CURATED, FIX);
        assertThat(pend.outcome()).isEqualTo(PilotOpsOutcome.PENDING_REVIEW);
        assertThat(pend.wroteChange()).isTrue();
        assertThat(qaRepository.findById(id).orElseThrow().getCurationStatus())
                .isEqualTo(KnowledgeCurationStatus.PENDING_REVIEW);

        // validate-one → VALIDATED, and crucially still UNPUBLISHED (never published/indexed here).
        PilotOpsResult val = ops.validateOne(SOURCE_CURATED, FIX, REVIEWER);
        assertThat(val.outcome()).isEqualTo(PilotOpsOutcome.VALIDATED);
        assertThat(val.wroteChange()).isTrue();
        assertThat(val.render()).endsWith("RESULT=VALIDATED");

        KnowledgeQuestionAnswer validated = qaRepository.findById(id).orElseThrow();
        assertThat(validated.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
        assertThat(validated.isPublished()).isFalse();
        assertThat(validated.isEligibleForRag()).isTrue();
        assertThat(embeddingRows(id)).isZero();
        assertThat(auditCount(id, "KNOWLEDGE_QA_VALIDATED", curatorId)).isEqualTo(1);
    }

    // =========================================================================
    // idempotency
    // =========================================================================

    @Test @Order(3)
    @DisplayName("idempotency: re-importing and re-validating the VALIDATED target is NO_CHANGE and never duplicates")
    void idempotentRepeats() {
        long before = qaCount(SOURCE_CURATED, FIX);

        // Re-import the existing key → NO_CHANGE, and no duplicate candidate is created.
        PilotOpsResult imp = ops.importOne(
                SOURCE_CURATED, FIX, "outra pergunta", "outra resposta");
        assertThat(imp.outcome()).isEqualTo(PilotOpsOutcome.NO_CHANGE);
        assertThat(imp.wroteChange()).isFalse();
        assertThat(qaCount(SOURCE_CURATED, FIX)).isEqualTo(before);

        // Re-validate the already-VALIDATED target → NO_CHANGE (end state already reached).
        PilotOpsResult val = ops.validateOne(SOURCE_CURATED, FIX, REVIEWER);
        assertThat(val.outcome()).isEqualTo(PilotOpsOutcome.NO_CHANGE);
        assertThat(val.wroteChange()).isFalse();
    }

    // =========================================================================
    // invalid states — fail closed
    // =========================================================================

    @Test @Order(4)
    @DisplayName("invalid: validate before pending, and validate with no source, are BLOCKED")
    void invalidValidateTransitions() {
        // A fresh candidate, curated but still IMPORTED (never moved to PENDING_REVIEW).
        ops.importOne(SOURCE_CURATED, NO_SOURCE_KEY, "pergunta sem fonte", "resposta bruta");
        ops.curateOne(SOURCE_CURATED, NO_SOURCE_KEY,
                "curta", "tecnica", null, KnowledgeTopic.IVA, null, "PT",
                KnowledgeRiskLevel.LOW, false, null);

        // validate before pending-review → BLOCKED (wrong status).
        PilotOpsResult early = ops.validateOne(SOURCE_CURATED, NO_SOURCE_KEY, REVIEWER);
        assertThat(early.outcome()).isEqualTo(PilotOpsOutcome.BLOCKED);
        assertThat(early.wroteChange()).isFalse();

        // Move to pending → PENDING_REVIEW, and a repeated pending-review is idempotent (NO_CHANGE).
        PilotOpsResult pend = ops.pendingReviewOne(SOURCE_CURATED, NO_SOURCE_KEY);
        assertThat(pend.outcome()).isEqualTo(PilotOpsOutcome.PENDING_REVIEW);
        PilotOpsResult pendAgain = ops.pendingReviewOne(SOURCE_CURATED, NO_SOURCE_KEY);
        assertThat(pendAgain.outcome()).isEqualTo(PilotOpsOutcome.NO_CHANGE);
        assertThat(pendAgain.wroteChange()).isFalse();

        // ...but with no source, validate is BLOCKED (service enforces ≥1 source).
        PilotOpsResult noSource = ops.validateOne(SOURCE_CURATED, NO_SOURCE_KEY, REVIEWER);
        assertThat(noSource.outcome()).isEqualTo(PilotOpsOutcome.BLOCKED);
        assertThat(noSource.details()).anyMatch(l -> l.contains("source"));
        assertThat(qaRepository.findBySourceSystemAndExternalKey(SOURCE_CURATED, NO_SOURCE_KEY)
                .get(0).getCurationStatus()).isEqualTo(KnowledgeCurationStatus.PENDING_REVIEW);
    }

    @Test @Order(5)
    @DisplayName("invalid: pending-review on a VALIDATED entry is BLOCKED (wrong status)")
    void pendingReviewOnValidatedIsBlocked() {
        PilotOpsResult r = ops.pendingReviewOne(SOURCE_CURATED, FIX); // FIX is VALIDATED from Order(2)
        assertThat(r.outcome()).isEqualTo(PilotOpsOutcome.BLOCKED);
        assertThat(r.wroteChange()).isFalse();
        assertThat(qaRepository.findBySourceSystemAndExternalKey(SOURCE_CURATED, FIX)
                .get(0).getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
    }

    @Test @Order(6)
    @DisplayName("resolution: a missing key and a malformed source system are BLOCKED before any write")
    void missingAndMalformedAreBlocked() {
        PilotOpsResult missing = ops.pendingReviewOne(SOURCE_CURATED, MISSING_KEY);
        assertThat(missing.outcome()).isEqualTo(PilotOpsOutcome.BLOCKED);
        assertThat(missing.details()).anyMatch(l -> l.contains("no Q&A found"));

        for (String bad : List.of("*", "%", "at-faq,taxia-curated", "at faq", "  ")) {
            PilotOpsResult r = ops.curateOne(bad, FIX, "x", null, null, null, null, null, null, null, null);
            assertThat(r.outcome())
                    .as("sourceSystem '%s' must be BLOCKED", bad)
                    .isEqualTo(PilotOpsOutcome.BLOCKED);
        }

        // A blank curator target still fails closed on the sourceSystem before touching the curator.
        PilotOpsResult nullSystem =
                ops.validateOne(null, FIX, REVIEWER);
        assertThat(nullSystem.outcome()).isEqualTo(PilotOpsOutcome.BLOCKED);
        assertThat(nullSystem.details()).anyMatch(l -> l.contains("sourceSystem is required"));
    }

    // =========================================================================
    // base gate — forbidden (non-pilot) database
    // =========================================================================

    @Test @Order(7)
    @DisplayName("base gate: any action is BLOCKED when the datasource is not knowledgeflow_pilot")
    void baseGateBlocksForbiddenDatabase() {
        jdbc.execute("CREATE DATABASE knowledgeflow");
        DriverManagerDataSource forbidden = new DriverManagerDataSource(
                "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort()
                        + "/knowledgeflow",
                postgres.getUsername(), postgres.getPassword());
        forbidden.setDriverClassName("org.postgresql.Driver");

        TaxiaPilotOperations forbiddenOps = new TaxiaPilotOperations(
                qaRepository, importService, curationService,
                organizationRepository, userRepository, forbidden, jdbc);

        PilotOpsResult provision = forbiddenOps.provisionActors();
        assertThat(provision.outcome()).isEqualTo(PilotOpsOutcome.BLOCKED);
        assertThat(provision.details()).anyMatch(l -> l.startsWith("base=BLOCKED"));

        PilotOpsResult validate = forbiddenOps.validateOne(SOURCE_CURATED, FIX, REVIEWER);
        assertThat(validate.outcome()).isEqualTo(PilotOpsOutcome.BLOCKED);
        assertThat(validate.details()).anyMatch(l -> l.startsWith("base=BLOCKED"));
    }

    // =========================================================================
    // hard N=1 limit — ambiguous key across organizations (runs last: adds a 2nd org)
    // =========================================================================

    @Test @Order(8)
    @DisplayName("N=1: an ambiguous key (>1 match across orgs) is BLOCKED, never acted on")
    void ambiguousKeyIsBlocked() {
        Organization otherOrg = organizationRepository.save(new Organization("Org — Outra Ops E9C", null));
        saveRawImported(org, AMBIGUOUS_KEY);
        saveRawImported(otherOrg, AMBIGUOUS_KEY);
        assertThat(qaRepository.findBySourceSystemAndExternalKey(SOURCE_CURATED, AMBIGUOUS_KEY)).hasSize(2);

        PilotOpsResult r = ops.pendingReviewOne(SOURCE_CURATED, AMBIGUOUS_KEY);
        assertThat(r.outcome()).isEqualTo(PilotOpsOutcome.BLOCKED);
        assertThat(r.wroteChange()).isFalse();
        assertThat(r.details()).anyMatch(l -> l.contains("ambiguous") && l.contains("N=1 only"));

        // Neither row advanced.
        assertThat(qaRepository.findBySourceSystemAndExternalKey(SOURCE_CURATED, AMBIGUOUS_KEY))
                .allMatch(qa -> qa.getCurationStatus() == KnowledgeCurationStatus.IMPORTED);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UUID insertPilotAdmin() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, full_name, password_hash, status, created_at, updated_at) "
                        + "VALUES (?::uuid, ?, ?, ?, ?, now(), now())",
                id.toString(), TaxiaPilotOperations.CURATOR_EMAIL, "Admin Piloto Ops",
                "LOCKED-NO-LOGIN-TEST", "ACTIVE");
        return id;
    }

    /** A raw IMPORTED entry saved directly (bypassing the tooling) — used only for the ambiguity test. */
    private KnowledgeQuestionAnswer saveRawImported(Organization organization, String externalKey) {
        KnowledgeQuestionAnswer qa = new KnowledgeQuestionAnswer(
                organization, "Pergunta " + externalKey, "Resposta " + externalKey,
                SOURCE_CURATED, externalKey);
        return qaRepository.save(qa);
    }

    private boolean userExists(UUID id) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?::uuid", Long.class, id.toString());
        return n != null && n > 0;
    }

    private long qaCount(String sourceSystem, String externalKey) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_question_answers "
                        + "WHERE source_system = ? AND external_key = ?",
                Long.class, sourceSystem, externalKey);
        return n != null ? n : 0L;
    }

    private long sourceCount(UUID qaId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_source_references WHERE knowledge_qa_id = ?::uuid",
                Long.class, qaId.toString());
        return n != null ? n : 0L;
    }

    private long embeddingRows(UUID qaId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                Long.class, qaId.toString());
        return n != null ? n : 0L;
    }

    private long auditCount(UUID qaId, String action, UUID userId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE entity_id = ?::uuid AND action = ? "
                        + "AND user_id = ?::uuid",
                Long.class, qaId.toString(), action, userId.toString());
        return n != null ? n : 0L;
    }
}
