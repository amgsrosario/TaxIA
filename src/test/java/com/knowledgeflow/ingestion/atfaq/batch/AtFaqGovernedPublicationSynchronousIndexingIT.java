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
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Governed AT-FAQ publication with a <b>real, synchronous</b> embedding indexer, in an isolated
 * database (Testcontainers) — Bloco E, E9C-real-executor-fix (PROMPT 86).
 *
 * <p>Frase-mestra: "Provar que, quando {@code publish(...)} indexa sincronamente, o executor
 * governado <em>observa</em> o embedding persistido e reporta a verdade — sem tocar na base piloto,
 * sem modelo de embeddings real e sem provider externo."
 *
 * <p>Its sibling {@link AtFaqGovernedPublicationExecutorIT} proves the <em>stub</em> path: publication
 * reaches {@code publishedAt}/{@code publishedBy} while the no-op indexer creates zero embeddings.
 * This IT proves the complementary path. It wires the <b>real</b>
 * {@link KnowledgeQaEmbeddingIndexerImpl} — fed by a deterministic in-test embedding, never an
 * external provider — into a dedicated {@link KnowledgeQuestionAnswerPublicationService}, so the real
 * {@code publish(...)} indexes synchronously (line 73 of that service: it indexes <em>before</em>
 * marking published). The governed executor then observes the persisted state and reports:
 * <ol>
 *   <li>mode {@code TEST_ISOLATED_WITH_SYNCHRONOUS_INDEXER} (derived from the observed embedding,
 *       never assumed);</li>
 *   <li>{@code published == true}, {@code indexed == true}, {@code embeddingPresent == true},
 *       {@code ragExpectedToRetrieve == true}; totals {@code published=1, indexed=1, embeddings=1,
 *       ragExpected=1};</li>
 *   <li>exactly one row in {@code knowledge_qa_embeddings} for the Q&amp;A (the unique constraint
 *       guarantees never more), proven by a direct {@code COUNT};</li>
 *   <li>a real {@link RagSearchService} retrieves the published+indexed case;</li>
 *   <li>a second (idempotent) run publishes nothing yet still truthfully observes the persisted
 *       embedding — no fourth item, still exactly one row;</li>
 *   <li>a skipped draft (not future-eligible) and a blocked draft (risk != LOW) create no embedding,
 *       so they never gain a RAG voice.</li>
 * </ol>
 *
 * <p>Isolation: the datasource is the local pgvector container, never {@code knowledgeflow_pilot};
 * the fixtures are clearly synthetic and reuse no real AT-FAQ identifier.
 *
 * <p>Run: mvn verify -Ppgtest -Dit.test=AtFaqGovernedPublicationSynchronousIndexingIT
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AtFaqGovernedPublicationSynchronousIndexingIT {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-22T11:00:00Z");
    private static final String PUBLISHED_BY = "taxia-governed-publication-test";
    private static final String SOURCE_SYSTEM = "at-faq-sync-indexing-isolated";
    private static final String RAG_QUERY = "Qual o enquadramento em IVA desta operação?";
    private static final int DIM = 768;
    private static final int TOP_K = 50;

    // Clearly synthetic id — never a real known AT-FAQ / ANP identifier.
    private static final String CANDIDATE = "SYNCIDX-FIX-001";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired KnowledgeSourceReferenceRepository sourceRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired AuditService auditService;
    @Autowired JdbcTemplate jdbc;

    private Organization org;
    private AtFaqGovernedPublicationExecutor executor;
    private RagSearchService ragSearch;

    private UUID candidateQaId;
    private AtFaqDraftPersistenceResult persistenceResult;
    private AtFaqGovernedPublicationExecutionResult firstRun;

    @BeforeAll
    void setUp() {
        org = organizationRepository.save(new Organization("Org — Publicação síncrona E9C", null));

        // The real publish(...) records an audit event; Postgres enforces the audit → users FK.
        provisionActor(AtFaqGovernedPublicationExecutor.deterministicActor(PUBLISHED_BY),
                "sync-indexing@taxia.test", "TaxIA Sync Indexing (service account)");

        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        EmbeddingService embedding = fixedEmbedding();

        // REAL indexer fed by the deterministic embedding → genuine pgvector rows, no external call.
        KnowledgeQaEmbeddingIndexerImpl realIndexer = new KnowledgeQaEmbeddingIndexerImpl(embedding, jdbc);

        // Publication service wired with the REAL indexer, so publish(...) indexes synchronously.
        KnowledgeQuestionAnswerPublicationService realPublicationService =
                new KnowledgeQuestionAnswerPublicationService(
                        qaRepository, sourceRepository, realIndexer, auditService,
                        new KnowledgeFlowMetrics(new SimpleMeterRegistry()));

        executor = new AtFaqGovernedPublicationExecutor(
                realPublicationService, qaRepository, sourceRepository, jdbc, clock);

        ragSearch = new RagSearchService(
                embedding, jdbc,
                new EmbeddingProperties(null, TOP_K, null, null, null, null, null),
                new KnowledgeFlowMetrics(new SimpleMeterRegistry()));

        KnowledgeQuestionAnswer qa = newImportedQaWithOfficialSource(CANDIDATE);
        candidateQaId = qa.getId();
        persistenceResult = wrap(eligibleDraft(CANDIDATE, candidateQaId));
    }

    // =========================================================================
    // P1 — publicação governada indexa sincronamente: 1 publicado, 1 indexado, 1 embedding
    // =========================================================================

    @Test @Order(1)
    @DisplayName("P1: publish indexa sincronamente — mode SYNCHRONOUS, published/indexed/embeddings/ragExpected = 1")
    void governedPublicationIndexesSynchronously() {
        // Isolamento do datasource: container local, nunca base piloto real.
        assertThat(postgres.getDatabaseName()).isNotEqualTo("knowledgeflow_pilot");
        assertThat(embeddingRowsFor(candidateQaId)).isZero(); // ainda sem voz antes de publicar

        firstRun = executor.publishGoverned(persistenceResult, org, PUBLISHED_BY);

        assertThat(firstRun.mode())
                .isEqualTo(AtFaqGovernedPublicationExecutionMode.TEST_ISOLATED_WITH_SYNCHRONOUS_INDEXER);
        assertThat(firstRun.executedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(firstRun.blockingErrors()).isEmpty();

        AtFaqGovernedPublicationExecutionTotals t = firstRun.totals();
        assertThat(t.totalPersistedDrafts()).isEqualTo(1);
        assertThat(t.eligibleForGovernedPublication()).isEqualTo(1);
        assertThat(t.validated()).isEqualTo(1);
        assertThat(t.published()).isEqualTo(1);
        assertThat(t.skipped()).isZero();
        assertThat(t.blocked()).isZero();
        // Reais e observados — não hard-zero.
        assertThat(t.indexed()).isEqualTo(1);
        assertThat(t.embeddings()).isEqualTo(1);
        assertThat(t.ragExpected()).isEqualTo(1);

        assertThat(firstRun.itemResults()).hasSize(1);
        AtFaqGovernedPublicationExecutionItemResult item = firstRun.itemResults().get(0);
        assertThat(item.externalId()).isEqualTo(CANDIDATE);
        assertThat(item.published()).isTrue();
        assertThat(item.validated()).isTrue();
        assertThat(item.indexed()).isTrue();
        assertThat(item.embeddingPresent()).isTrue();
        assertThat(item.ragExpectedToRetrieve()).isTrue();
        assertThat(item.eligibleForRagByEntityRules()).isTrue();
        assertThat(item.curationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
        assertThat(item.blockingReasons()).isEmpty();

        // Estado persistido: exactamente uma linha de embedding para a Q&A.
        assertThat(embeddingRowsFor(candidateQaId)).isEqualTo(1);
        assertThat(countEmbeddingsForOrg(org)).isEqualTo(1);
        KnowledgeQuestionAnswer qa = qaRepository.findById(candidateQaId).orElseThrow();
        assertThat(qa.isPublished()).isTrue();
        assertThat(qa.getPublishedBy()).isEqualTo(PUBLISHED_BY);
    }

    // =========================================================================
    // P2 — o RAG recupera o caso publicado e indexado
    // =========================================================================

    @Test @Order(2)
    @DisplayName("P2: RAG recupera o Q&A publicado e indexado sincronamente")
    void ragRetrievesPublishedIndexedCase() {
        List<UUID> retrieved = ragSearch.findSimilar(org.getId(), RAG_QUERY).stream()
                .map(RagSearchService.RetrievedCase::sourceQaId)
                .toList();
        assertThat(retrieved).contains(candidateQaId);
    }

    // =========================================================================
    // P3 — idempotência: segunda execução não republica, mas observa o embedding persistido
    // =========================================================================

    @Test @Order(3)
    @DisplayName("P3: segunda execução é idempotente — published=0, skipped=1, embedding persistido continua observado")
    void secondRunIsIdempotentAndStillObservesEmbedding() {
        AtFaqGovernedPublicationExecutionResult secondRun =
                executor.publishGoverned(persistenceResult, org, PUBLISHED_BY);

        assertThat(secondRun.blockingErrors()).isEmpty();
        assertThat(secondRun.totals().published()).isZero();
        assertThat(secondRun.totals().validated()).isZero();
        assertThat(secondRun.totals().blocked()).isZero();
        assertThat(secondRun.totals().skipped()).isEqualTo(1);

        // Já publicado num run anterior → o executor observa o embedding que continua persistido.
        assertThat(secondRun.mode())
                .isEqualTo(AtFaqGovernedPublicationExecutionMode.TEST_ISOLATED_WITH_SYNCHRONOUS_INDEXER);
        assertThat(secondRun.totals().indexed()).isEqualTo(1);
        assertThat(secondRun.totals().embeddings()).isEqualTo(1);
        assertThat(secondRun.totals().ragExpected()).isEqualTo(1);

        AtFaqGovernedPublicationExecutionItemResult item = secondRun.itemResults().get(0);
        assertThat(item.published()).isTrue();
        assertThat(item.validated()).isFalse();
        assertThat(item.embeddingPresent()).isTrue();

        // Sem quarto item, ainda exactamente uma linha.
        assertThat(embeddingRowsFor(candidateQaId)).isEqualTo(1);
        assertThat(countEmbeddingsForOrg(org)).isEqualTo(1);
    }

    // =========================================================================
    // P4 — guarda negativa: skipped (não elegível) e blocked (risco != LOW) não criam embedding
    // =========================================================================

    @Test @Order(4)
    @DisplayName("P4: draft ignorado e draft bloqueado não produzem embedding nem voz no RAG")
    void skippedAndBlockedProduceNoEmbedding() {
        // Skipped: não elegível para autonomia futura → nunca chega a publish(), zero embedding.
        KnowledgeQuestionAnswer skippedQa = newImportedQaWithOfficialSource("SYNCIDX-SKIP-002");
        AtFaqGovernedPublicationExecutionResult skippedRun = executor.publishGoverned(
                wrap(draft("SYNCIDX-SKIP-002", skippedQa.getId(), false, false, officialSignals())),
                org, PUBLISHED_BY);
        assertThat(skippedRun.totals().published()).isZero();
        assertThat(skippedRun.totals().skipped()).isEqualTo(1);
        assertThat(skippedRun.totals().embeddings()).isZero();
        assertThat(skippedRun.itemResults().get(0).embeddingPresent()).isFalse();
        assertThat(embeddingRowsFor(skippedQa.getId())).isZero();

        // Blocked: risco != LOW → guarda recusa antes de publicar, zero embedding.
        KnowledgeQuestionAnswer blockedQa =
                newImportedQaWithOfficialSource("SYNCIDX-BLOCK-003", KnowledgeRiskLevel.HIGH);
        AtFaqGovernedPublicationExecutionResult blockedRun = executor.publishGoverned(
                wrap(draft("SYNCIDX-BLOCK-003", blockedQa.getId(), true, false, officialSignals())),
                org, PUBLISHED_BY);
        assertThat(blockedRun.totals().published()).isZero();
        assertThat(blockedRun.totals().blocked()).isEqualTo(1);
        assertThat(blockedRun.totals().embeddings()).isZero();
        assertThat(blockedRun.itemResults().get(0).embeddingPresent()).isFalse();
        assertThat(blockedRun.mode())
                .isEqualTo(AtFaqGovernedPublicationExecutionMode.TEST_ISOLATED_WITH_STUB_INDEXER);
        assertThat(embeddingRowsFor(blockedQa.getId())).isZero();

        // O candidato publicado no P1 continua o único com voz.
        assertThat(countEmbeddingsForOrg(org)).isEqualTo(1);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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
        return newImportedQaWithOfficialSource(externalKey, KnowledgeRiskLevel.LOW);
    }

    private KnowledgeQuestionAnswer newImportedQaWithOfficialSource(
            String externalKey, KnowledgeRiskLevel risk) {
        KnowledgeQuestionAnswer qa = new KnowledgeQuestionAnswer(
                org, "Pergunta " + externalKey, "Resposta " + externalKey,
                SOURCE_SYSTEM, externalKey);
        qa.updateCuration(
                "Pergunta normalizada " + externalKey,
                "Resposta curta " + externalKey,
                "Resposta técnica completa " + externalKey + ".",
                KnowledgeTopic.IVA, null, "PT", risk, false, null, null, null);
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
        return draft(externalId, qaId, true, false, officialSignals());
    }

    private static AtFaqDraftPersistenceItemResult draft(
            String externalId, UUID qaId, boolean autoFuture, boolean humanNeeded,
            List<String> signals) {
        return new AtFaqDraftPersistenceItemResult(
                externalId, true, true, true, false, false, qaId,
                "Pergunta normalizada " + externalId,
                KnowledgeCurationStatus.IMPORTED, autoFuture, humanNeeded,
                signals, List.of(), List.of(), List.of());
    }

    private static AtFaqDraftPersistenceResult wrap(AtFaqDraftPersistenceItemResult item) {
        return new AtFaqDraftPersistenceResult(
                "SYNCIDX", FIXED_INSTANT, "test", AtFaqDraftPersistenceMode.DRY_RUN_VERIFIED,
                new AtFaqDraftPersistenceTotals(1, 1, 1, 1, 0, 0, 1, 0, 0, 0, 0),
                List.of(item), List.of(), List.of(), List.of());
    }

    private void provisionActor(UUID actorId, String email, String fullName) {
        jdbc.update(
                "INSERT INTO users (id, email, full_name, password_hash, status, created_at, updated_at) "
                        + "VALUES (?::uuid, ?, ?, ?, ?, now(), now())",
                actorId.toString(), email, fullName, "n/a", "ACTIVE");
    }

    private long embeddingRowsFor(UUID qaId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                Long.class, qaId.toString());
        return n != null ? n : 0L;
    }

    private long countEmbeddingsForOrg(Organization organization) {
        Long n = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM knowledge_qa_embeddings kqae
                JOIN knowledge_question_answers kqa ON kqa.id = kqae.knowledge_qa_id
                WHERE kqa.organization_id = ?::uuid
                """,
                Long.class, organization.getId().toString());
        return n != null ? n : 0L;
    }
}
