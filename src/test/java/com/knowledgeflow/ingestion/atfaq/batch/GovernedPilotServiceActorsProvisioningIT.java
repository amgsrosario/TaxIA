package com.knowledgeflow.ingestion.atfaq.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.audit.service.AuditService;
import com.knowledgeflow.common.observability.KnowledgeFlowMetrics;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Dedicated governed-pilot service actors, provisioned and exercised in an isolated database
 * (Testcontainers) — Bloco E, E9C-pilot-actor-prep (PROMPT 88).
 *
 * <p>Frase-mestra: "A publicação e o rollback governados do piloto agem sob uma identidade técnica
 * própria, auditável e sem login humano — provado em base efémera, nunca na {@code knowledgeflow_pilot},
 * nunca sob {@code piloto.admin}."
 *
 * <p>This IT proves the {@link GovernedPilotServiceActors} strategy end-to-end, using the production
 * provisioner itself (never a hand-rolled INSERT):
 * <ol>
 *   <li><b>Provisioning</b> — {@link GovernedPilotServiceActors#provision(JdbcTemplate)} creates the
 *       two non-login technical users with the expected deterministic ids, emails, names and status;
 *       a second call is idempotent (inserts nothing, no duplicates).</li>
 *   <li><b>Publication</b> — a synthetic LOW candidate is published feeding
 *       {@link GovernedPilotServiceActors#PUBLISHER_IDENTITY} as {@code publishedBy}; the
 *       {@code KNOWLEDGE_QA_PUBLISHED} audit event points at
 *       {@link GovernedPilotServiceActors#publisherActorId()} — no human user is used.</li>
 *   <li><b>Rollback</b> — the same case is indexed (E9A) then rolled back feeding
 *       {@link GovernedPilotServiceActors#ROLLBACK_IDENTITY}; the {@code KNOWLEDGE_QA_UNPUBLISHED}
 *       audit event points at {@link GovernedPilotServiceActors#rollbackActorId()}, distinct from the
 *       publisher, with the mandatory {@code reasonCode} persisted.</li>
 *   <li><b>Non-login</b> — both actors are {@code DISABLED}, hold no {@code organization_users}
 *       membership, and carry a password hash that no {@link BCryptPasswordEncoder} candidate matches.</li>
 * </ol>
 *
 * <p>Isolation: the datasource is the local pgvector container, never {@code knowledgeflow_pilot};
 * the fixtures are clearly synthetic and reuse no real AT-FAQ / ANP identifier.
 *
 * <p>Run: mvn verify -Ppgtest -Dit.test=GovernedPilotServiceActorsProvisioningIT
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GovernedPilotServiceActorsProvisioningIT {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-22T12:00:00Z");
    private static final String INDEXED_BY = "taxia-e9c-pilot-indexer";
    private static final String SOURCE_SYSTEM = "at-faq-pilot-actor-isolated";
    private static final String RAG_QUERY = "Qual o enquadramento em IVA desta operação?";
    private static final AtFaqRollbackMotive MOTIVE = AtFaqRollbackMotive.of(
            AtFaqRollbackReason.LEGAL_CHANGE, "resposta desatualizada; retirar do RAG.");
    private static final int DIM = 768;

    // Clearly synthetic id — never a real known AT-FAQ / ANP identifier.
    private static final String CANDIDATE = "PILOTACTOR-FIX-001";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired KnowledgeSourceReferenceRepository sourceRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired KnowledgeQuestionAnswerPublicationService publicationService; // pgtest stub — publish
    @Autowired AuditService auditService;
    @Autowired JdbcTemplate jdbc;

    private Organization org;
    private AtFaqGovernedPublicationExecutor executor;
    private AtFaqGovernedRagIndexingService ragService;
    private AtFaqGovernedRollbackService rollbackService;

    private UUID candidateQaId;
    private AtFaqDraftPersistenceResult persistenceResult;
    private AtFaqGovernedPublicationExecutionResult firstRun;
    private AtFaqRagIndexingResult indexResult;

    @BeforeAll
    void setUp() {
        org = organizationRepository.save(new Organization("Org — Actor técnico E9C piloto", null));

        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        EmbeddingService embedding = fixedEmbedding();
        KnowledgeQaEmbeddingIndexerImpl realIndexer = new KnowledgeQaEmbeddingIndexerImpl(embedding, jdbc);

        // Publish via the pgtest stub (0 embeddings); E9A gives controlled voice with the real indexer.
        executor = new AtFaqGovernedPublicationExecutor(
                publicationService, qaRepository, sourceRepository, jdbc, clock);
        ragService = new AtFaqGovernedRagIndexingService(
                realIndexer, qaRepository, sourceRepository, jdbc, clock);

        RagSearchService ragSearch = new RagSearchService(
                embedding, jdbc,
                new EmbeddingProperties(null, 5, null, null, null, null, null),
                new KnowledgeFlowMetrics(new SimpleMeterRegistry()));
        AtFaqRollbackRagProbe probe = (organizationId, qaId) ->
                ragSearch.findSimilar(organizationId, RAG_QUERY).stream()
                        .anyMatch(r -> qaId.equals(r.sourceQaId()));

        // Rollback service uses a publication service wired with the SAME real indexer so unpublish
        // truly deletes the embedding (the pgtest-autowired stub would no-op).
        KnowledgeQuestionAnswerPublicationService realPublicationService =
                new KnowledgeQuestionAnswerPublicationService(
                        qaRepository, sourceRepository, realIndexer, auditService,
                        new KnowledgeFlowMetrics(new SimpleMeterRegistry()));
        rollbackService = new AtFaqGovernedRollbackService(
                realPublicationService, qaRepository, jdbc, probe, clock);

        KnowledgeQuestionAnswer qa = newImportedQaWithOfficialSource(CANDIDATE);
        candidateQaId = qa.getId();
        persistenceResult = wrap(eligibleDraft(CANDIDATE, candidateQaId));
    }

    // =========================================================================
    // P1 — provisionamento isolado do actor técnico dedicado (via produção)
    // =========================================================================

    @Test @Order(1)
    @DisplayName("P1: provision() cria os dois actores técnicos com id/email/nome/estado esperados")
    void provisioningCreatesBothTechnicalActors() {
        assertThat(postgres.getDatabaseName()).isNotEqualTo("knowledgeflow_pilot");

        int inserted = GovernedPilotServiceActors.provision(jdbc);
        assertThat(inserted).isEqualTo(2);

        UUID publisherId = GovernedPilotServiceActors.publisherActorId();
        UUID rollbackId = GovernedPilotServiceActors.rollbackActorId();
        assertThat(publisherId).isNotEqualTo(rollbackId);

        assertActorRow(publisherId, GovernedPilotServiceActors.PUBLISHER_EMAIL,
                GovernedPilotServiceActors.PUBLISHER_FULL_NAME);
        assertActorRow(rollbackId, GovernedPilotServiceActors.ROLLBACK_EMAIL,
                GovernedPilotServiceActors.ROLLBACK_FULL_NAME);
    }

    // =========================================================================
    // P2 — idempotência do provisionamento
    // =========================================================================

    @Test @Order(2)
    @DisplayName("P2: provision() é idempotente — segunda chamada insere 0 e não duplica")
    void provisioningIsIdempotent() {
        int insertedAgain = GovernedPilotServiceActors.provision(jdbc);
        assertThat(insertedAgain).isZero();

        assertThat(userCount(GovernedPilotServiceActors.publisherActorId())).isEqualTo(1L);
        assertThat(userCount(GovernedPilotServiceActors.rollbackActorId())).isEqualTo(1L);
    }

    // =========================================================================
    // P3 — não-login provado (DISABLED + sem membership + password inutilizável)
    // =========================================================================

    @Test @Order(3)
    @DisplayName("P3: actores técnicos não têm login — DISABLED, sem membership, hash não-BCrypt")
    void technicalActorsCannotLogin() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        for (UUID actorId : List.of(
                GovernedPilotServiceActors.publisherActorId(),
                GovernedPilotServiceActors.rollbackActorId())) {
            assertThat(statusOf(actorId)).isEqualTo("DISABLED");
            assertThat(membershipCount(actorId)).isZero();
            String hash = passwordHashOf(actorId);
            assertThat(hash).isEqualTo(GovernedPilotServiceActors.NON_LOGIN_PASSWORD_HASH);
            // Não é um hash BCrypt → nenhuma password corresponde.
            assertThat(encoder.matches("", hash)).isFalse();
            assertThat(encoder.matches("password", hash)).isFalse();
            assertThat(encoder.matches(GovernedPilotServiceActors.NON_LOGIN_PASSWORD_HASH, hash)).isFalse();
        }
    }

    // =========================================================================
    // P4 — publicação governada sob o publisher técnico; auditoria aponta-lhe
    // =========================================================================

    @Test @Order(4)
    @DisplayName("P4: publicação usa o publisher técnico — audit PUBLISHED aponta o actor esperado")
    void publicationUsesTechnicalPublisherActor() {
        firstRun = executor.publishGoverned(
                persistenceResult, org, GovernedPilotServiceActors.PUBLISHER_IDENTITY);

        assertThat(firstRun.blockingErrors()).isEmpty();
        assertThat(firstRun.totals().published()).isEqualTo(1);

        KnowledgeQuestionAnswer qa = qaRepository.findById(candidateQaId).orElseThrow();
        assertThat(qa.isPublished()).isTrue();
        assertThat(qa.getPublishedBy()).isEqualTo(GovernedPilotServiceActors.PUBLISHER_IDENTITY);

        // Auditoria aponta exactamente para o publisher técnico — nunca um utilizador humano.
        assertThat(latestActor(candidateQaId, "KNOWLEDGE_QA_PUBLISHED"))
                .isEqualTo(GovernedPilotServiceActors.publisherActorId().toString());
    }

    // =========================================================================
    // P5 — rollback governado sob o rollback actor técnico; auditoria aponta-lhe
    // =========================================================================

    @Test @Order(5)
    @DisplayName("P5: rollback usa o rollback actor técnico — audit UNPUBLISHED aponta o actor esperado + reasonCode")
    void rollbackUsesTechnicalRollbackActor() {
        // E9A: dá voz controlada ao Q&A publicado (embedding real, sem provider externo).
        indexResult = ragService.indexSinglePublishedQa(firstRun, org, INDEXED_BY);
        assertThat(embeddingRowsFor(candidateQaId)).isEqualTo(1);

        AtFaqRollbackResult rollback = rollbackService.rollbackSingleIndexedQa(
                indexResult, org, GovernedPilotServiceActors.ROLLBACK_IDENTITY, MOTIVE);

        assertThat(rollback.blockingErrors()).isEmpty();
        assertThat(rollback.totals().rolledBack()).isEqualTo(1);
        assertThat(rollback.rolledBackBy()).isEqualTo(GovernedPilotServiceActors.ROLLBACK_IDENTITY);
        assertThat(embeddingRowsFor(candidateQaId)).isZero();

        // Auditoria de rollback aponta para o rollback actor técnico, distinto do publisher.
        String rollbackActor = latestActor(candidateQaId, "KNOWLEDGE_QA_UNPUBLISHED");
        assertThat(rollbackActor)
                .isEqualTo(GovernedPilotServiceActors.rollbackActorId().toString());
        assertThat(rollbackActor)
                .isNotEqualTo(GovernedPilotServiceActors.publisherActorId().toString());

        // reasonCode persistido no metadata do evento.
        assertThat(latestMetadata(candidateQaId, "KNOWLEDGE_QA_UNPUBLISHED"))
                .startsWith("reasonCode=LEGAL_CHANGE");
    }

    // =========================================================================
    // P6 — idempotência: reexecuções não criam actor novo, ids estáveis
    // =========================================================================

    @Test @Order(6)
    @DisplayName("P6: reexecutar publicação/rollback e provisionar de novo não cria actores novos")
    void reExecutionDoesNotCreateNewActors() {
        long publishersBefore = userCount(GovernedPilotServiceActors.publisherActorId());
        long rollbacksBefore = userCount(GovernedPilotServiceActors.rollbackActorId());

        // Provisionar de novo: 0 inserções.
        assertThat(GovernedPilotServiceActors.provision(jdbc)).isZero();

        // Republicar (já publicado → skipped) não toca nos actores.
        executor.publishGoverned(persistenceResult, org, GovernedPilotServiceActors.PUBLISHER_IDENTITY);
        // Rollback de novo (já revertido → skipped) não toca nos actores.
        rollbackService.rollbackSingleIndexedQa(
                indexResult, org, GovernedPilotServiceActors.ROLLBACK_IDENTITY, MOTIVE);

        assertThat(userCount(GovernedPilotServiceActors.publisherActorId())).isEqualTo(publishersBefore);
        assertThat(userCount(GovernedPilotServiceActors.rollbackActorId())).isEqualTo(rollbacksBefore);
        // ids permanecem estáveis entre chamadas.
        assertThat(GovernedPilotServiceActors.publisherActorId())
                .isEqualTo(AtFaqGovernedPublicationExecutor.deterministicActor(
                        GovernedPilotServiceActors.PUBLISHER_IDENTITY));
        assertThat(GovernedPilotServiceActors.rollbackActorId())
                .isEqualTo(AtFaqGovernedRollbackService.deterministicActor(
                        GovernedPilotServiceActors.ROLLBACK_IDENTITY));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void assertActorRow(UUID actorId, String email, String fullName) {
        assertThat(userCount(actorId)).isEqualTo(1L);
        assertThat(emailOf(actorId)).isEqualTo(email);
        assertThat(fullNameOf(actorId)).isEqualTo(fullName);
        assertThat(statusOf(actorId)).isEqualTo("DISABLED");
        assertThat(membershipCount(actorId)).isZero();
    }

    private long userCount(UUID id) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?::uuid", Long.class, id.toString());
        return n != null ? n : 0L;
    }

    private String emailOf(UUID id) {
        return jdbc.queryForObject(
                "SELECT email FROM users WHERE id = ?::uuid", String.class, id.toString());
    }

    private String fullNameOf(UUID id) {
        return jdbc.queryForObject(
                "SELECT full_name FROM users WHERE id = ?::uuid", String.class, id.toString());
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject(
                "SELECT status FROM users WHERE id = ?::uuid", String.class, id.toString());
    }

    private String passwordHashOf(UUID id) {
        return jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?::uuid", String.class, id.toString());
    }

    private long membershipCount(UUID userId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM organization_users WHERE user_id = ?::uuid",
                Long.class, userId.toString());
        return n != null ? n : 0L;
    }

    private String latestActor(UUID qaId, String action) {
        return jdbc.queryForObject(
                "SELECT user_id::text FROM audit_events WHERE entity_id = ?::uuid AND action = ? "
                        + "ORDER BY occurred_at DESC LIMIT 1",
                String.class, qaId.toString(), action);
    }

    private String latestMetadata(UUID qaId, String action) {
        return jdbc.queryForObject(
                "SELECT metadata FROM audit_events WHERE entity_id = ?::uuid AND action = ? "
                        + "ORDER BY occurred_at DESC LIMIT 1",
                String.class, qaId.toString(), action);
    }

    private long embeddingRowsFor(UUID qaId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                Long.class, qaId.toString());
        return n != null ? n : 0L;
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

    private KnowledgeQuestionAnswer newImportedQaWithOfficialSource(String externalKey) {
        KnowledgeQuestionAnswer qa = new KnowledgeQuestionAnswer(
                org, "Pergunta " + externalKey, "Resposta " + externalKey,
                SOURCE_SYSTEM, externalKey);
        qa.updateCuration(
                "Pergunta normalizada " + externalKey,
                "Resposta curta " + externalKey,
                "Resposta técnica completa " + externalKey + ".",
                KnowledgeTopic.IVA, null, "PT", KnowledgeRiskLevel.LOW, false, null, null, null);
        qa = qaRepository.save(qa);

        KnowledgeSourceReference source = new KnowledgeSourceReference(
                qa, KnowledgeSourceType.LEGISLATION, "Fonte oficial " + externalKey);
        source.update(KnowledgeSourceType.LEGISLATION, "Fonte oficial", "Artigo 41.º do CIVA",
                null, null, null, null, null, null);
        sourceRepository.save(source);
        return qa;
    }

    private static List<String> officialSignals() {
        return List.of("AUTO_PUBLICATION_FUTURE_ELIGIBLE", "OFFICIAL_SOURCE_PRESENT",
                "LEGAL_REFERENCE_PRESENT", "TECHNICAL_ANSWER_PRESENT", "LOW_RISK",
                "NO_CONFLICTS", "NO_BLOCKING_DUPLICATE");
    }

    private static AtFaqDraftPersistenceItemResult eligibleDraft(String externalId, UUID qaId) {
        return new AtFaqDraftPersistenceItemResult(
                externalId, true, true, true, false, false, qaId,
                "Pergunta normalizada " + externalId,
                KnowledgeCurationStatus.IMPORTED, true, false,
                officialSignals(), List.of(), List.of(), List.of());
    }

    private static AtFaqDraftPersistenceResult wrap(AtFaqDraftPersistenceItemResult item) {
        return new AtFaqDraftPersistenceResult(
                "PILOTACTOR", FIXED_INSTANT, "test", AtFaqDraftPersistenceMode.DRY_RUN_VERIFIED,
                new AtFaqDraftPersistenceTotals(1, 1, 1, 1, 0, 0, 1, 0, 0, 0, 0),
                List.of(item), List.of(), List.of(), List.of());
    }
}
