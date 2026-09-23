package com.knowledgeflow.ingestion.atfaq.pilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.audit.service.AuditService;
import com.knowledgeflow.common.observability.KnowledgeFlowMetrics;
import com.knowledgeflow.ingestion.atfaq.AtFaqProperties;
import com.knowledgeflow.ingestion.atfaq.batch.AtFaqRollbackMotive;
import com.knowledgeflow.ingestion.atfaq.batch.AtFaqRollbackReason;
import com.knowledgeflow.ingestion.atfaq.batch.GovernedPilotServiceActors;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.rag.KnowledgeQaEmbeddingIndexerImpl;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerPublicationService;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.rag.EmbeddingProperties;
import com.knowledgeflow.rag.EmbeddingService;
import com.knowledgeflow.rag.RagSearchService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
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
 * Proves the guarded one-shot governed pilot runner end-to-end in an <b>isolated</b> database —
 * Bloco E, E9C-pilot-runner (PROMPT 93).
 *
 * <p>Frase-mestra: "Um gesto, um alvo, no máximo uma escrita — e sempre reversível, auditável e
 * fechado por defeito."
 *
 * <p>The database is an ephemeral Testcontainers pgvector instance <em>named</em>
 * {@code knowledgeflow_pilot} purely so the runner's reused base gate
 * ({@link com.knowledgeflow.config.pilot.PilotDatasourceGuard#validate}) exercises its accept-path —
 * it is NOT the real pilot database. The runner is wired with a dedicated
 * {@link KnowledgeQuestionAnswerPublicationService} backed by the <b>real</b>
 * {@link KnowledgeQaEmbeddingIndexerImpl} fed a deterministic in-test embedding (never an external
 * provider), so {@code publish-one} genuinely indexes and {@code rollback-one} genuinely de-indexes.
 * The dedicated non-login technical actors are provisioned via {@link GovernedPilotServiceActors}.
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@code status} is read-only and runs with the E9C flag OFF (READY / BLOCKED-missing /
 *       BLOCKED-ambiguous);</li>
 *   <li>{@code publish-one} publishes exactly one Q&amp;A, creates exactly one embedding, is RAG
 *       retrievable, and is idempotent (NO_CHANGE on repeat);</li>
 *   <li>{@code rollback-one} unpublishes + de-indexes exactly one Q&amp;A with a mandatory motive,
 *       records the audit reason, drops RAG voice, and is idempotent;</li>
 *   <li>every guard fails closed: writes blocked when the flag is OFF, rollback blocked without a
 *       motive, and the base gate blocks a forbidden (non-pilot) database;</li>
 *   <li>the hard N=1 limit: an ambiguous key (&gt;1 match) is refused, never acted on.</li>
 * </ul>
 *
 * <p>Run: mvn verify -Ppgtest -Dit.test=TaxiaPilotGovernedRunnerIT
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TaxiaPilotGovernedRunnerIT {

    private static final String SOURCE_SYSTEM = "at-faq-pilot-runner-isolated";
    private static final String FIX_001 = "PILOT-RUNNER-FIX-001";
    private static final String FIX_002 = "PILOT-RUNNER-FIX-002";
    private static final String DUP_KEY = "PILOT-RUNNER-DUP-777";
    private static final String MISSING_KEY = "PILOT-RUNNER-DOES-NOT-EXIST";
    private static final String RAG_QUERY = "Qual o enquadramento em IVA desta operação?";
    private static final int DIM = 768;
    private static final int TOP_K = 50;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("knowledgeflow_pilot");

    @Autowired KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired KnowledgeSourceReferenceRepository sourceRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired AuditService auditService;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private Organization org;
    private Organization otherOrg;
    private TaxiaPilotGovernedRunner runnerFlagOn;
    private TaxiaPilotGovernedRunner runnerFlagOff;
    private RagSearchService ragSearch;

    private UUID fix001QaId;

    @BeforeAll
    void setUp() {
        org = organizationRepository.save(new Organization("Org — Pilot Runner E9C", null));
        otherOrg = organizationRepository.save(new Organization("Org — Outra E9C", null));

        // Real governed publish/unpublish record audit events; Postgres enforces audit → users FK.
        GovernedPilotServiceActors.provision(jdbc);

        EmbeddingService embedding = fixedEmbedding();
        KnowledgeQaEmbeddingIndexerImpl realIndexer = new KnowledgeQaEmbeddingIndexerImpl(embedding, jdbc);
        KnowledgeQuestionAnswerPublicationService realPublicationService =
                new KnowledgeQuestionAnswerPublicationService(
                        qaRepository, sourceRepository, realIndexer, auditService,
                        new KnowledgeFlowMetrics(new SimpleMeterRegistry()));

        runnerFlagOn = new TaxiaPilotGovernedRunner(
                qaRepository, realPublicationService, props(true), dataSource, jdbc);
        runnerFlagOff = new TaxiaPilotGovernedRunner(
                qaRepository, realPublicationService, props(false), dataSource, jdbc);

        ragSearch = new RagSearchService(
                embedding, jdbc,
                new EmbeddingProperties(null, TOP_K, null, null, null, null, null),
                new KnowledgeFlowMetrics(new SimpleMeterRegistry()));

        fix001QaId = newEligibleQa(org, FIX_001).getId();
    }

    // =========================================================================
    // status — read-only preflight, runs with the flag OFF
    // =========================================================================

    @Test @Order(1)
    @DisplayName("status: READY, read-only, runs with E9C flag OFF")
    void statusReadyReadOnlyWithFlagOff() {
        assertThat(embeddingRows(fix001QaId)).isZero();

        PilotRunnerResult r = runnerFlagOff.status(FIX_001);

        assertThat(r.outcome()).isEqualTo(PilotRunnerOutcome.READY);
        assertThat(r.wroteChange()).isFalse();
        assertThat(r.terminalLine()).isEqualTo("READINESS=READY");
        assertThat(r.render()).endsWith("READINESS=READY");
        assertThat(r.details()).anyMatch(l -> l.equals("flagEnabled=false"));
        assertThat(r.details()).anyMatch(l -> l.equals("published=false"));
        // READINESS=READY is environment health, NOT write authorization: with the flag off the
        // separate writeReadiness line must say BLOCKED so READY can never be misread as "publish-ready".
        assertThat(r.details()).anyMatch(
                l -> l.startsWith("writeReadiness=BLOCKED") && l.contains("AT_FAQ_E9C_PILOT_ENABLED=false"));

        // Read-only: nothing changed.
        assertThat(embeddingRows(fix001QaId)).isZero();
        assertThat(qaRepository.findById(fix001QaId).orElseThrow().isPublished()).isFalse();
    }

    @Test @Order(12)
    @DisplayName("status: with E9C flag ON, READY reports writeReadiness=READY — still read-only")
    void statusFlagOnReportsWriteReady() {
        KnowledgeQuestionAnswer qa = newEligibleQa(org, "PILOT-RUNNER-STATUS-ON");
        UUID id = qa.getId();
        assertThat(embeddingRows(id)).isZero();

        PilotRunnerResult r = runnerFlagOn.status("PILOT-RUNNER-STATUS-ON");

        assertThat(r.outcome()).isEqualTo(PilotRunnerOutcome.READY);
        assertThat(r.wroteChange()).isFalse();
        assertThat(r.render()).endsWith("READINESS=READY");
        assertThat(r.details()).anyMatch(
                l -> l.startsWith("writeReadiness=READY") && l.contains("AT_FAQ_E9C_PILOT_ENABLED=true"));

        // Read-only: reporting write-ready must not itself write anything.
        assertThat(embeddingRows(id)).isZero();
        assertThat(qaRepository.findById(id).orElseThrow().isPublished()).isFalse();
    }

    @Test @Order(2)
    @DisplayName("status: BLOCKED when the external key resolves to nothing")
    void statusBlockedWhenMissing() {
        PilotRunnerResult r = runnerFlagOn.status(MISSING_KEY);
        assertThat(r.outcome()).isEqualTo(PilotRunnerOutcome.BLOCKED);
        assertThat(r.terminalLine()).isEqualTo("READINESS=BLOCKED");
        assertThat(r.details()).anyMatch(l -> l.contains("no Q&A found"));
    }

    @Test @Order(3)
    @DisplayName("status: BLOCKED when the external key is ambiguous (>1 match) — hard N=1 limit")
    void statusBlockedWhenAmbiguous() {
        // Same (sourceSystem, externalKey) in two organizations → 2 matches across orgs.
        newEligibleQa(org, DUP_KEY);
        newEligibleQa(otherOrg, DUP_KEY);

        PilotRunnerResult r = runnerFlagOn.status(DUP_KEY);
        assertThat(r.outcome()).isEqualTo(PilotRunnerOutcome.BLOCKED);
        assertThat(r.details()).anyMatch(l -> l.contains("ambiguous") && l.contains("N=1 only"));
    }

    // =========================================================================
    // publish-one
    // =========================================================================

    @Test @Order(4)
    @DisplayName("publish-one: BLOCKED when the E9C flag is OFF — no write")
    void publishBlockedWhenFlagOff() {
        PilotRunnerResult r = runnerFlagOff.publishOne(FIX_001);
        assertThat(r.outcome()).isEqualTo(PilotRunnerOutcome.BLOCKED);
        assertThat(r.wroteChange()).isFalse();
        assertThat(r.terminalLine()).isEqualTo("RESULT=BLOCKED");
        assertThat(r.details()).anyMatch(l -> l.contains("AT_FAQ_E9C_PILOT_ENABLED"));

        assertThat(embeddingRows(fix001QaId)).isZero();
        assertThat(qaRepository.findById(fix001QaId).orElseThrow().isPublished()).isFalse();
    }

    @Test @Order(5)
    @DisplayName("publish-one: PUBLISHED exactly one Q&A — one embedding, RAG retrievable, audited")
    void publishOneSucceeds() {
        PilotRunnerResult r = runnerFlagOn.publishOne(FIX_001);

        assertThat(r.outcome()).isEqualTo(PilotRunnerOutcome.PUBLISHED);
        assertThat(r.wroteChange()).isTrue();
        assertThat(r.targetQaId()).isEqualTo(fix001QaId);
        assertThat(r.render()).endsWith("RESULT=PUBLISHED");

        KnowledgeQuestionAnswer qa = qaRepository.findById(fix001QaId).orElseThrow();
        assertThat(qa.isPublished()).isTrue();
        assertThat(qa.getPublishedBy()).isEqualTo(GovernedPilotServiceActors.PUBLISHER_IDENTITY);
        assertThat(embeddingRows(fix001QaId)).isEqualTo(1);

        // Genuine RAG voice.
        List<UUID> retrieved = ragSearch.findSimilar(org.getId(), RAG_QUERY).stream()
                .map(RagSearchService.RetrievedCase::sourceQaId)
                .toList();
        assertThat(retrieved).contains(fix001QaId);

        // Audited under the dedicated publisher actor.
        assertThat(auditCount(fix001QaId, "KNOWLEDGE_QA_PUBLISHED",
                GovernedPilotServiceActors.publisherActorId())).isEqualTo(1);
    }

    @Test @Order(6)
    @DisplayName("publish-one: NO_CHANGE when already published (idempotent) — still one embedding")
    void publishIdempotent() {
        PilotRunnerResult r = runnerFlagOn.publishOne(FIX_001);
        assertThat(r.outcome()).isEqualTo(PilotRunnerOutcome.NO_CHANGE);
        assertThat(r.wroteChange()).isFalse();
        assertThat(r.render()).endsWith("RESULT=NO_CHANGE");
        assertThat(embeddingRows(fix001QaId)).isEqualTo(1);
    }

    // =========================================================================
    // rollback-one
    // =========================================================================

    @Test @Order(7)
    @DisplayName("rollback-one: BLOCKED without a motive — target stays published")
    void rollbackBlockedWhenNoMotive() {
        PilotRunnerResult r = runnerFlagOn.rollbackOne(FIX_001, null);
        assertThat(r.outcome()).isEqualTo(PilotRunnerOutcome.BLOCKED);
        assertThat(r.wroteChange()).isFalse();
        assertThat(r.details()).anyMatch(l -> l.contains("motive"));
        assertThat(qaRepository.findById(fix001QaId).orElseThrow().isPublished()).isTrue();
        assertThat(embeddingRows(fix001QaId)).isEqualTo(1);
    }

    @Test @Order(8)
    @DisplayName("rollback-one: BLOCKED when the E9C flag is OFF — target stays published")
    void rollbackBlockedWhenFlagOff() {
        AtFaqRollbackMotive motive = AtFaqRollbackMotive.of(AtFaqRollbackReason.LEGAL_CHANGE, "teste");
        PilotRunnerResult r = runnerFlagOff.rollbackOne(FIX_001, motive);
        assertThat(r.outcome()).isEqualTo(PilotRunnerOutcome.BLOCKED);
        assertThat(r.wroteChange()).isFalse();
        assertThat(qaRepository.findById(fix001QaId).orElseThrow().isPublished()).isTrue();
        assertThat(embeddingRows(fix001QaId)).isEqualTo(1);
    }

    @Test @Order(9)
    @DisplayName("rollback-one: ROLLED_BACK exactly one Q&A — de-indexed, no RAG voice, audited reason")
    void rollbackOneSucceeds() {
        AtFaqRollbackMotive motive = AtFaqRollbackMotive.of(
                AtFaqRollbackReason.LEGAL_CHANGE, "resposta desatualizada; retirar do RAG.");

        PilotRunnerResult r = runnerFlagOn.rollbackOne(FIX_001, motive);

        assertThat(r.outcome()).isEqualTo(PilotRunnerOutcome.ROLLED_BACK);
        assertThat(r.wroteChange()).isTrue();
        assertThat(r.targetQaId()).isEqualTo(fix001QaId);
        assertThat(r.render()).endsWith("RESULT=ROLLED_BACK");

        KnowledgeQuestionAnswer qa = qaRepository.findById(fix001QaId).orElseThrow();
        assertThat(qa.isPublished()).isFalse();
        assertThat(embeddingRows(fix001QaId)).isZero();

        // No RAG voice after rollback.
        List<UUID> retrieved = ragSearch.findSimilar(org.getId(), RAG_QUERY).stream()
                .map(RagSearchService.RetrievedCase::sourceQaId)
                .toList();
        assertThat(retrieved).doesNotContain(fix001QaId);

        // Audited under the dedicated rollback actor, with the reason preserved.
        assertThat(auditCount(fix001QaId, "KNOWLEDGE_QA_UNPUBLISHED",
                GovernedPilotServiceActors.rollbackActorId())).isEqualTo(1);
        assertThat(latestUnpublishMetadata(fix001QaId)).contains("reasonCode=LEGAL_CHANGE");
    }

    @Test @Order(10)
    @DisplayName("rollback-one: NO_CHANGE when not published (idempotent)")
    void rollbackIdempotent() {
        AtFaqRollbackMotive motive = AtFaqRollbackMotive.of(AtFaqRollbackReason.LEGAL_CHANGE, "repeat");
        PilotRunnerResult r = runnerFlagOn.rollbackOne(FIX_001, motive);
        assertThat(r.outcome()).isEqualTo(PilotRunnerOutcome.NO_CHANGE);
        assertThat(r.wroteChange()).isFalse();
        assertThat(r.render()).endsWith("RESULT=NO_CHANGE");
    }

    // =========================================================================
    // base gate — forbidden (non-pilot) database
    // =========================================================================

    @Test @Order(11)
    @DisplayName("base gate: any action is BLOCKED when the datasource is not knowledgeflow_pilot")
    void baseGateBlocksForbiddenDatabase() {
        jdbc.execute("CREATE DATABASE knowledgeflow");
        DriverManagerDataSource forbidden = new DriverManagerDataSource(
                "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort()
                        + "/knowledgeflow",
                postgres.getUsername(), postgres.getPassword());
        forbidden.setDriverClassName("org.postgresql.Driver");

        TaxiaPilotGovernedRunner forbiddenRunner = new TaxiaPilotGovernedRunner(
                qaRepository, null, props(true), forbidden, jdbc);

        PilotRunnerResult status = forbiddenRunner.status(FIX_002);
        assertThat(status.outcome()).isEqualTo(PilotRunnerOutcome.BLOCKED);
        assertThat(status.details()).anyMatch(l -> l.startsWith("base=BLOCKED"));

        // publishOne also stops at the base gate before touching the (null) publication service.
        PilotRunnerResult publish = forbiddenRunner.publishOne(FIX_002);
        assertThat(publish.outcome()).isEqualTo(PilotRunnerOutcome.BLOCKED);
        assertThat(publish.details()).anyMatch(l -> l.startsWith("base=BLOCKED"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private AtFaqProperties props(boolean e9cEnabled) {
        AtFaqProperties p = new AtFaqProperties();
        p.setSourceSystem(SOURCE_SYSTEM);
        p.setE9cPilotEnabled(e9cEnabled);
        return p;
    }

    /** Deterministic 768-dim embedding: first component 1.0, remaining 0.0 — same for query/passage. */
    private static EmbeddingService fixedEmbedding() {
        List<Float> vec = new ArrayList<>(DIM);
        vec.add(1.0f);
        for (int i = 1; i < DIM; i++) vec.add(0.0f);
        List<Float> immutable = List.copyOf(vec);
        return new EmbeddingService() {
            @Override public List<Float> embedQuery(String text) { return immutable; }
            @Override public List<Float> embedPassage(String text) { return immutable; }
        };
    }

    /** Creates a VALIDATED, LOW-risk Q&A with one official source — eligible for governed publication. */
    private KnowledgeQuestionAnswer newEligibleQa(Organization organization, String externalKey) {
        KnowledgeQuestionAnswer qa = new KnowledgeQuestionAnswer(
                organization, "Pergunta " + externalKey, "Resposta " + externalKey,
                SOURCE_SYSTEM, externalKey);
        qa.updateCuration(
                "Pergunta normalizada " + externalKey,
                "Resposta curta " + externalKey,
                "Resposta técnica completa " + externalKey + ".",
                KnowledgeTopic.IVA, null, "PT", KnowledgeRiskLevel.LOW, false, null, null, null);
        qa.markPendingReview();
        qa.validate("test-validator");
        qa = qaRepository.save(qa);

        KnowledgeSourceReference source = new KnowledgeSourceReference(
                qa, KnowledgeSourceType.LEGISLATION, "Fonte oficial " + externalKey);
        source.update(KnowledgeSourceType.LEGISLATION, "Fonte oficial", "Artigo 41.º do CIVA",
                null, null, null, null, null, null);
        sourceRepository.save(source);
        return qa;
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

    private String latestUnpublishMetadata(UUID qaId) {
        return jdbc.queryForObject(
                "SELECT metadata FROM audit_events WHERE entity_id = ?::uuid "
                        + "AND action = 'KNOWLEDGE_QA_UNPUBLISHED' ORDER BY occurred_at DESC LIMIT 1",
                String.class, qaId.toString());
    }
}
