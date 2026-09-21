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
 * Effective governed rollback of a <em>small batch</em> of published/indexed Q&A in an isolated
 * database — Bloco E, E10B.
 *
 * <p>E10B frase-mestra: "Calar uma voz prova o travão. Calar um pequeno coro prova a governação."
 *
 * <p>This IT is the batch counterpart of {@link AtFaqGovernedRollbackServiceIT} (E10A). It creates
 * real published + VALIDATED Q&A, gives each one controlled voice through the <b>real</b>
 * {@link KnowledgeQaEmbeddingIndexerImpl} (one row in {@code knowledge_qa_embeddings}, retrievable by
 * a real {@link RagSearchService}), then drives {@link AtFaqGovernedRollbackService#rollbackSmallIndexedBatch}
 * against a {@link KnowledgeQuestionAnswerPublicationService} constructed here with the <b>same real
 * indexer</b> (never the {@code pgtest} stub), so each {@code unpublish(...)} genuinely deletes the
 * embedding (desindexar) and clears the publication (despublicar). A RAG probe backed by the same
 * deterministic embedding proves each Q&A is retrieved before and no longer retrieved after.
 *
 * <p>It proves the genuine small-batch rollback cycle without the real embedding model, without any
 * external call and without ever touching the real pilot base:
 * <ul>
 *   <li><b>Cenário A</b> — N=3 principal: 3 eligible Q&A rolled back together (maxItems=3);</li>
 *   <li><b>Cenário B</b> — 4 eligible + maxItems=3: exactly 3 rolled back, 1 deferred (never dropped);</li>
 *   <li><b>Cenário C</b> — idempotência: a second batch over already rolled-back Q&A is skipped, never thrown;</li>
 *   <li><b>Cenário D</b> — guardas: reason vazio, org errada, maxItems &lt; 2, maxItems &gt; 3, lote
 *       sem embeddings, itens não indexados;</li>
 *   <li><b>Preservação E10A</b> — o modo single continua a reverter exactamente 1 Q&A.</li>
 * </ul>
 *
 * <p>Run: mvn verify -Ppgtest -Dit.test=AtFaqGovernedRollbackBatchServiceIT
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AtFaqGovernedRollbackBatchServiceIT {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-07T09:20:00Z");
    private static final String PUBLISHED_BY = "taxia-governed-publication-test";
    private static final String INDEXED_BY = "taxia-governed-indexing-test";
    private static final String ROLLED_BACK_BY = "taxia-governed-rollback-test";
    private static final String SOURCE_SYSTEM = "at-faq-governed-batch";
    private static final AtFaqRollbackMotive MOTIVE = AtFaqRollbackMotive.of(
            AtFaqRollbackReason.LEGAL_CHANGE, "respostas desatualizadas; retirar o coro do RAG.");
    private static final String RAG_QUERY = "Qual o limiar do volume de negócios para IVA mensal?";
    private static final int DIM = 768;
    // Generous top-k: the deterministic embedding is identical for every Q&A, so a small k could
    // truncate a live target on ties. A large k guarantees every live embedding is returned, making
    // the before/after RAG assertions deterministic.
    private static final int TOP_K = 50;

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
    private Organization otherOrg;
    private AtFaqGovernedRollbackService rollbackService;
    private RagSearchService ragSearch;
    private KnowledgeQaEmbeddingIndexerImpl realIndexer;

    // Shared across ordered tests: the three Q&A rolled back in Cenário A, reused for idempotency (C).
    private final List<UUID> scenarioAQaIds = new ArrayList<>();
    private final List<String> scenarioAExternalIds = new ArrayList<>();

    @BeforeAll
    void setUp() {
        org = organizationRepository.save(new Organization("Org — Rollback E10B", null));
        otherOrg = organizationRepository.save(new Organization("Org — Outra E10B", null));

        // The real unpublish(...) records audit events; Postgres enforces audit → users FK.
        provisionActor(AtFaqGovernedRollbackService.deterministicActor(ROLLED_BACK_BY),
                "governed-rollback-batch@taxia.test", "TaxIA Governed Rollback Batch (service account)");

        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

        // Shared real indexer + deterministic embedding (no external calls).
        EmbeddingService embedding = fixedEmbedding();
        realIndexer = new KnowledgeQaEmbeddingIndexerImpl(embedding, jdbc);

        // Real RAG search fed by the same deterministic embedding, and a probe over it.
        ragSearch = new RagSearchService(
                embedding, jdbc,
                new EmbeddingProperties(null, TOP_K, null, null, null, null, null),
                new KnowledgeFlowMetrics(new SimpleMeterRegistry()));
        AtFaqRollbackRagProbe probe = (organizationId, qaId) ->
                ragSearch.findSimilar(organizationId, RAG_QUERY).stream()
                        .anyMatch(r -> qaId.equals(r.sourceQaId()));

        // Rollback service wired with a PublicationService using the SAME real indexer,
        // so unpublish(...) truly deletes the embedding (the pgtest-autowired one would no-op).
        KnowledgeQuestionAnswerPublicationService realPublicationService =
                new KnowledgeQuestionAnswerPublicationService(
                        qaRepository, sourceRepository, realIndexer, auditService,
                        new KnowledgeFlowMetrics(new SimpleMeterRegistry()));
        rollbackService = new AtFaqGovernedRollbackService(
                realPublicationService, qaRepository, jdbc, probe, clock);
    }

    // =========================================================================
    // CENÁRIO A — N=3 principal
    // =========================================================================

    @Test @Order(1)
    @DisplayName("A1: pré-condição — 3 Q&A publicados, VALIDATED, com 1 embedding cada e RAG recupera-os")
    void batchPreconditionThreeIndexed() {
        for (String ext : List.of("AT-FAQ-2001", "AT-FAQ-2002", "AT-FAQ-2003")) {
            KnowledgeQuestionAnswer qa = newIndexedPublishedQa(ext);
            scenarioAQaIds.add(qa.getId());
            scenarioAExternalIds.add(ext);
        }
        for (UUID qaId : scenarioAQaIds) {
            KnowledgeQuestionAnswer qa = qaRepository.findById(qaId).orElseThrow();
            assertThat(qa.isPublished()).isTrue();
            assertThat(qa.getPublishedAt()).isNotNull();
            assertThat(qa.getPublishedBy()).isNotNull();
            assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
            assertThat(embeddingRowsFor(qaId)).isEqualTo(1);
            assertThat(ragRetrieves(qaId)).isTrue();
        }
    }

    @Test @Order(2)
    @DisplayName("A2: rollback de lote pequeno reverte exactamente os 3 Q&A (maxItems=3, com motivo)")
    void rollsBackSmallBatchOfThree() {
        AtFaqRagIndexingResult index = wrapIndex(
                indexItem(scenarioAExternalIds.get(0), scenarioAQaIds.get(0)),
                indexItem(scenarioAExternalIds.get(1), scenarioAQaIds.get(1)),
                indexItem(scenarioAExternalIds.get(2), scenarioAQaIds.get(2)));

        AtFaqRollbackResult run =
                rollbackService.rollbackSmallIndexedBatch(index, org, ROLLED_BACK_BY, MOTIVE, 3);

        assertThat(run.mode()).isEqualTo(AtFaqRollbackMode.SMALL_BATCH_TEST_ISOLATED);
        assertThat(run.rolledBackAt()).isEqualTo(FIXED_INSTANT);
        assertThat(run.rolledBackBy()).isEqualTo(ROLLED_BACK_BY);
        assertThat(run.blockingErrors()).isEmpty();
        assertThat(run.requestedMaxItems()).isEqualTo(3);
        assertThat(run.effectiveMaxItems()).isEqualTo(3);

        AtFaqRollbackTotals t = run.totals();
        assertThat(t.eligibleForRollback()).isEqualTo(3);
        assertThat(t.rolledBack()).isEqualTo(3);
        assertThat(t.unpublished()).isEqualTo(3);
        assertThat(t.deindexed()).isEqualTo(3);
        assertThat(t.batchSize()).isEqualTo(3);
        assertThat(t.embeddingRowsBefore()).isEqualTo(3);
        assertThat(t.embeddingRowsAfter()).isZero();
        assertThat(t.ragRecoveredBefore()).isEqualTo(3);
        assertThat(t.ragRecoveredAfter()).isZero();
        assertThat(t.skipped()).isZero();
        assertThat(t.deferredDueToLimit()).isZero();
        assertThat(t.skippedAlreadyRolledBack()).isZero();
        assertThat(t.skippedNotIndexed()).isZero();
        assertThat(t.blocked()).isZero();

        assertThat(run.itemResults()).hasSize(3);
        assertThat(run.itemResults()).allSatisfy(item -> {
            assertThat(item.rolledBack()).isTrue();
            assertThat(item.unpublished()).isTrue();
            assertThat(item.deindexed()).isTrue();
            assertThat(item.embeddingRemoved()).isTrue();
            assertThat(item.ragRecoveredBefore()).isTrue();
            assertThat(item.ragRecoveredAfter()).isFalse();
            assertThat(item.reason()).isEqualTo(MOTIVE.auditDetail());
        });
    }

    @Test @Order(3)
    @DisplayName("A3: depois do lote — cada Q&A despublicado, VALIDATED, 0 embeddings, RAG não recupera")
    void stateAfterBatchRollback() {
        for (UUID qaId : scenarioAQaIds) {
            KnowledgeQuestionAnswer qa = qaRepository.findById(qaId).orElseThrow();
            assertThat(qa.isPublished()).isFalse();
            assertThat(qa.getPublishedAt()).isNull();
            assertThat(qa.getPublishedBy()).isNull();
            // Rollback neutraliza a recuperação; não rebaixa a curadoria.
            assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
            assertThat(embeddingRowsFor(qaId)).isZero();
            assertThat(ragRetrieves(qaId)).isFalse();
            // Auditoria KNOWLEDGE_QA_UNPUBLISHED preservada para cada Q&A revertido.
            assertThat(unpublishAuditCount(qaId)).isGreaterThanOrEqualTo(1L);
        }
    }

    // =========================================================================
    // CENÁRIO B — 4 elegíveis + maxItems=3 → 3 rolledBack, 1 deferred
    // =========================================================================

    @Test @Order(4)
    @DisplayName("B: 4 elegíveis com maxItems=3 → 3 revertidos e 1 diferido (nunca perdido)")
    void fourEligibleWithLimitThreeDefersOne() {
        KnowledgeQuestionAnswer qa1 = newIndexedPublishedQa("AT-FAQ-2101");
        KnowledgeQuestionAnswer qa2 = newIndexedPublishedQa("AT-FAQ-2102");
        KnowledgeQuestionAnswer qa3 = newIndexedPublishedQa("AT-FAQ-2103");
        KnowledgeQuestionAnswer qa4 = newIndexedPublishedQa("AT-FAQ-2104");

        AtFaqRagIndexingResult index = wrapIndex(
                indexItem("AT-FAQ-2101", qa1.getId()),
                indexItem("AT-FAQ-2102", qa2.getId()),
                indexItem("AT-FAQ-2103", qa3.getId()),
                indexItem("AT-FAQ-2104", qa4.getId()));

        AtFaqRollbackResult run =
                rollbackService.rollbackSmallIndexedBatch(index, org, ROLLED_BACK_BY, MOTIVE, 3);

        assertThat(run.mode()).isEqualTo(AtFaqRollbackMode.SMALL_BATCH_TEST_ISOLATED);
        assertThat(run.blockingErrors()).isEmpty();

        AtFaqRollbackTotals t = run.totals();
        assertThat(t.rolledBack()).isEqualTo(3);
        assertThat(t.batchSize()).isEqualTo(3);
        assertThat(t.deferredDueToLimit()).isEqualTo(1);
        assertThat(t.eligibleForRollback()).isEqualTo(4); // 3 rolled + 1 deferred are all eligible
        assertThat(t.skipped()).isEqualTo(1);             // the deferred one
        assertThat(t.skippedAlreadyRolledBack()).isZero();
        assertThat(t.skippedNotIndexed()).isZero();
        assertThat(t.blocked()).isZero();
        assertThat(run.globalWarnings()).anyMatch(w -> w.toLowerCase().contains("diferido"));

        // Ordem previsível: os 3 primeiros revertidos, o 4.º diferido.
        assertThat(run.itemResults()).hasSize(4);
        for (int i = 0; i < 3; i++) {
            AtFaqRollbackItemResult item = run.itemResults().get(i);
            assertThat(item.rolledBack()).isTrue();
            assertThat(item.deferredDueToLimit()).isFalse();
        }
        AtFaqRollbackItemResult deferred = run.itemResults().get(3);
        assertThat(deferred.deferredDueToLimit()).isTrue();
        assertThat(deferred.eligibleForRollback()).isTrue();
        assertThat(deferred.rolledBack()).isFalse();

        // Os 3 revertidos: despublicados e sem embedding.
        for (KnowledgeQuestionAnswer qa : List.of(qa1, qa2, qa3)) {
            assertThat(qaRepository.findById(qa.getId()).orElseThrow().isPublished()).isFalse();
            assertThat(embeddingRowsFor(qa.getId())).isZero();
            assertThat(ragRetrieves(qa.getId())).isFalse();
        }
        // O diferido: continua publicado, indexado e recuperável.
        assertThat(qaRepository.findById(qa4.getId()).orElseThrow().isPublished()).isTrue();
        assertThat(embeddingRowsFor(qa4.getId())).isEqualTo(1);
        assertThat(ragRetrieves(qa4.getId())).isTrue();
    }

    // =========================================================================
    // CENÁRIO C — idempotência: segundo lote sobre já revertidos
    // =========================================================================

    @Test @Order(5)
    @DisplayName("C: segundo lote sobre Q&A já revertidos é idempotente/skipped (não rebenta, nada recriado)")
    void secondBatchIsIdempotent() {
        AtFaqRagIndexingResult index = wrapIndex(
                indexItem(scenarioAExternalIds.get(0), scenarioAQaIds.get(0)),
                indexItem(scenarioAExternalIds.get(1), scenarioAQaIds.get(1)),
                indexItem(scenarioAExternalIds.get(2), scenarioAQaIds.get(2)));

        AtFaqRollbackResult run =
                rollbackService.rollbackSmallIndexedBatch(index, org, ROLLED_BACK_BY, MOTIVE, 3);

        assertThat(run.blockingErrors()).isEmpty();
        AtFaqRollbackTotals t = run.totals();
        assertThat(t.rolledBack()).isZero();
        assertThat(t.skippedAlreadyRolledBack()).isEqualTo(3);
        assertThat(t.skipped()).isEqualTo(3);
        assertThat(t.deferredDueToLimit()).isZero();
        assertThat(t.blocked()).isZero();

        assertThat(run.itemResults()).hasSize(3);
        assertThat(run.itemResults()).allSatisfy(item -> {
            assertThat(item.alreadyRolledBack()).isTrue();
            assertThat(item.skippedAlreadyRolledBack()).isTrue();
            assertThat(item.rolledBack()).isFalse();
        });

        // Nada recriado: continuam sem publicação e sem embedding.
        for (UUID qaId : scenarioAQaIds) {
            assertThat(embeddingRowsFor(qaId)).isZero();
            assertThat(qaRepository.findById(qaId).orElseThrow().isPublished()).isFalse();
            assertThat(ragRetrieves(qaId)).isFalse();
        }
    }

    // =========================================================================
    // CENÁRIO D — guardas / validações
    // =========================================================================

    @Test @Order(6)
    @DisplayName("D1: motivo vazio bloqueia o lote (motivo obrigatório)")
    void emptyReasonBlocksBatch() {
        AtFaqRagIndexingResult index = wrapIndex(
                indexItem("AT-FAQ-2201", UUID.randomUUID()),
                indexItem("AT-FAQ-2202", UUID.randomUUID()));

        AtFaqRollbackResult run =
                rollbackService.rollbackSmallIndexedBatch(
                        index, org, ROLLED_BACK_BY, (AtFaqRollbackMotive) null, 3);

        assertThat(run.totals().rolledBack()).isZero();
        assertThat(run.blockingErrors()).anyMatch(e -> e.toLowerCase().contains("motivo"));
    }

    @Test @Order(7)
    @DisplayName("D2: Q&A de outra organização são todos bloqueados (isolamento por organização)")
    void wrongOrganizationBlocksEveryItem() {
        KnowledgeQuestionAnswer qaA = newIndexedPublishedQa("AT-FAQ-2211");
        KnowledgeQuestionAnswer qaB = newIndexedPublishedQa("AT-FAQ-2212");

        AtFaqRollbackResult run = rollbackService.rollbackSmallIndexedBatch(
                wrapIndex(indexItem("AT-FAQ-2211", qaA.getId()), indexItem("AT-FAQ-2212", qaB.getId())),
                otherOrg, ROLLED_BACK_BY, MOTIVE, 3);

        assertThat(run.totals().rolledBack()).isZero();
        assertThat(run.totals().blocked()).isEqualTo(2);
        assertThat(run.itemResults()).allSatisfy(item ->
                assertThat(item.blockingReasons()).anyMatch(r -> r.contains("não pertence à organização")));
        // Nada despublicado: ambos continuam publicados e indexados.
        assertThat(qaRepository.findById(qaA.getId()).orElseThrow().isPublished()).isTrue();
        assertThat(qaRepository.findById(qaB.getId()).orElseThrow().isPublished()).isTrue();
        assertThat(embeddingRowsFor(qaA.getId())).isEqualTo(1);
        assertThat(embeddingRowsFor(qaB.getId())).isEqualTo(1);
    }

    @Test @Order(8)
    @DisplayName("D3: maxItems < 2 é recusado (um único Q&A pertence ao modo single)")
    void maxItemsBelowTwoIsRejected() {
        AtFaqRagIndexingResult index = wrapIndex(
                indexItem("AT-FAQ-2221", UUID.randomUUID()),
                indexItem("AT-FAQ-2222", UUID.randomUUID()));

        AtFaqRollbackResult run =
                rollbackService.rollbackSmallIndexedBatch(index, org, ROLLED_BACK_BY, MOTIVE, 1);

        assertThat(run.totals().rolledBack()).isZero();
        assertThat(run.blockingErrors()).anyMatch(e -> e.toLowerCase().contains("maxitems"));
    }

    @Test @Order(9)
    @DisplayName("D4: maxItems > 3 é recusado (calar um pequeno coro não é calar em massa)")
    void maxItemsAboveThreeIsRejected() {
        AtFaqRagIndexingResult index = wrapIndex(
                indexItem("AT-FAQ-2231", UUID.randomUUID()),
                indexItem("AT-FAQ-2232", UUID.randomUUID()));

        AtFaqRollbackResult run =
                rollbackService.rollbackSmallIndexedBatch(index, org, ROLLED_BACK_BY, MOTIVE, 4);

        assertThat(run.totals().rolledBack()).isZero();
        assertThat(run.blockingErrors()).anyMatch(e -> e.contains("3"));
    }

    @Test @Order(10)
    @DisplayName("D5: lote publicado mas sem embedding (embeddingRows != 1) é bloqueado, não falha")
    void publishedWithoutEmbeddingIsBlocked() {
        KnowledgeQuestionAnswer qaA = newPublishedQa("AT-FAQ-2241", KnowledgeRiskLevel.LOW, org);
        KnowledgeQuestionAnswer qaB = newPublishedQa("AT-FAQ-2242", KnowledgeRiskLevel.LOW, org);
        assertThat(embeddingRowsFor(qaA.getId())).isZero(); // publicados, mas nunca indexados
        assertThat(embeddingRowsFor(qaB.getId())).isZero();

        AtFaqRollbackResult run = rollbackService.rollbackSmallIndexedBatch(
                wrapIndex(indexItem("AT-FAQ-2241", qaA.getId()), indexItem("AT-FAQ-2242", qaB.getId())),
                org, ROLLED_BACK_BY, MOTIVE, 3);

        assertThat(run.totals().rolledBack()).isZero();
        assertThat(run.totals().blocked()).isEqualTo(2);
        assertThat(run.itemResults()).allSatisfy(item ->
                assertThat(item.blockingReasons()).anyMatch(r -> r.contains("embeddingRowsBefore != 1")));
        // Guarda antes de qualquer efeito destrutivo: continuam publicados.
        assertThat(qaRepository.findById(qaA.getId()).orElseThrow().isPublished()).isTrue();
        assertThat(qaRepository.findById(qaB.getId()).orElseThrow().isPublished()).isTrue();
    }

    @Test @Order(11)
    @DisplayName("D6: itens não indexados são skipped (skippedNotIndexed), não bloqueiam nem falham")
    void notIndexedItemsAreSkipped() {
        AtFaqRollbackResult run = rollbackService.rollbackSmallIndexedBatch(
                wrapIndex(
                        notIndexedItem("AT-FAQ-2251", UUID.randomUUID()),
                        notIndexedItem("AT-FAQ-2252", UUID.randomUUID())),
                org, ROLLED_BACK_BY, MOTIVE, 3);

        assertThat(run.blockingErrors()).isEmpty();
        AtFaqRollbackTotals t = run.totals();
        assertThat(t.rolledBack()).isZero();
        assertThat(t.skippedNotIndexed()).isEqualTo(2);
        assertThat(t.skipped()).isEqualTo(2);
        assertThat(t.blocked()).isZero();
        assertThat(run.itemResults()).allSatisfy(item ->
                assertThat(item.skippedNotIndexed()).isTrue());
    }

    // =========================================================================
    // Preservação E10A — o modo single continua a reverter exactamente 1 Q&A
    // =========================================================================

    @Test @Order(12)
    @DisplayName("E10A preservada: rollbackSingleIndexedQa reverte exactamente 1 Q&A em modo single")
    void singleModeStillRollsBackExactlyOne() {
        KnowledgeQuestionAnswer qa = newIndexedPublishedQa("AT-FAQ-2301");

        AtFaqRollbackResult run = rollbackService.rollbackSingleIndexedQa(
                wrapIndex(indexItem("AT-FAQ-2301", qa.getId())), org, ROLLED_BACK_BY, MOTIVE);

        assertThat(run.mode()).isEqualTo(AtFaqRollbackMode.TEST_ISOLATED_SINGLE_QA);
        assertThat(run.blockingErrors()).isEmpty();
        assertThat(run.totals().rolledBack()).isEqualTo(1);
        assertThat(run.totals().batchSize()).isEqualTo(1);
        assertThat(run.itemResults()).hasSize(1);
        assertThat(run.itemResults().get(0).rolledBack()).isTrue();
        assertThat(qaRepository.findById(qa.getId()).orElseThrow().isPublished()).isFalse();
        assertThat(embeddingRowsFor(qa.getId())).isZero();
    }

    // =========================================================================
    // Higiene do relatório e isolamento
    // =========================================================================

    @Test @Order(13)
    @DisplayName("Zero chamadas externas — datasource ligado ao container local")
    void zeroExternalCalls() {
        assertThat(postgres.getJdbcUrl()).startsWith("jdbc:postgresql://");
        assertThat(postgres.getHost()).isIn("localhost", "127.0.0.1");
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

    private boolean ragRetrieves(UUID qaId) {
        return ragSearch.findSimilar(org.getId(), RAG_QUERY).stream()
                .anyMatch(r -> qaId.equals(r.sourceQaId()));
    }

    private long unpublishAuditCount(UUID qaId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE entity_id = ?::uuid AND action = ?",
                Long.class, qaId.toString(), "KNOWLEDGE_QA_UNPUBLISHED");
        return n != null ? n : 0L;
    }

    private void provisionActor(UUID actorId, String email, String fullName) {
        jdbc.update(
                "INSERT INTO users (id, email, full_name, password_hash, status, created_at, updated_at) "
                        + "VALUES (?::uuid, ?, ?, ?, ?, now(), now())",
                actorId.toString(), email, fullName, "n/a", "ACTIVE");
    }

    private KnowledgeQuestionAnswer newImportedQa(
            String externalKey, KnowledgeRiskLevel risk, Organization owner) {
        KnowledgeQuestionAnswer qa = new KnowledgeQuestionAnswer(
                owner, "Pergunta " + externalKey, "Resposta " + externalKey,
                SOURCE_SYSTEM, externalKey);
        qa.updateCuration(
                "Pergunta normalizada " + externalKey,
                "Resposta curta " + externalKey,
                "Resposta técnica completa " + externalKey + ".",
                KnowledgeTopic.IVA, null, "PT", risk, false, null, null, null);
        return qaRepository.save(qa);
    }

    /** Create a VALIDATED + published Q&A through the real domain API, with one legislation source. */
    private KnowledgeQuestionAnswer newPublishedQa(
            String externalKey, KnowledgeRiskLevel risk, Organization owner) {
        KnowledgeQuestionAnswer qa = newImportedQa(externalKey, risk, owner);
        addSource(qa, "Artigo 41.º do CIVA");
        qa.markPendingReview();
        qa.validate("test-reviewer");
        qa.markPublished(PUBLISHED_BY);
        return qaRepository.save(qa);
    }

    /** Published + VALIDATED Q&A that also gets controlled voice: one embedding via the real indexer. */
    private KnowledgeQuestionAnswer newIndexedPublishedQa(String externalKey) {
        KnowledgeQuestionAnswer qa = newPublishedQa(externalKey, KnowledgeRiskLevel.LOW, org);
        realIndexer.index(qa.getId(), qa.getOriginalQuestion(), qa.getTechnicalAnswer(),
                KnowledgeTopic.IVA.name());
        return qa;
    }

    private void addSource(KnowledgeQuestionAnswer qa, String legalReference) {
        KnowledgeSourceReference source = new KnowledgeSourceReference(
                qa, KnowledgeSourceType.LEGISLATION, "Fonte oficial " + qa.getExternalKey());
        source.update(KnowledgeSourceType.LEGISLATION, "Fonte oficial", legalReference,
                null, null, null, null, null, null);
        sourceRepository.save(source);
    }

    /** Synthetic "indexed" candidate pointing at an existing Q&A (embeddingPresent, 1 row claimed). */
    private static AtFaqRagIndexingItemResult indexItem(String externalId, UUID qaId) {
        return new AtFaqRagIndexingItemResult(
                externalId, qaId,
                true,  // eligibleForIndexing
                true,  // indexed
                true,  // embeddingPresent
                true,  // ragExpectedToRetrieve
                1,     // embeddingRows
                "Pergunta normalizada " + externalId,
                List.of(), List.of(), List.of());
    }

    /** Synthetic "not indexed" item: nothing was ever indexed for it (candidate == false). */
    private static AtFaqRagIndexingItemResult notIndexedItem(String externalId, UUID qaId) {
        return new AtFaqRagIndexingItemResult(
                externalId, qaId,
                false, // eligibleForIndexing
                false, // indexed
                false, // embeddingPresent
                false, // ragExpectedToRetrieve
                0,     // embeddingRows
                "Pergunta normalizada " + externalId,
                List.of(), List.of(), List.of());
    }

    private static AtFaqRagIndexingResult wrapIndex(AtFaqRagIndexingItemResult... items) {
        int indexed = 0;
        for (AtFaqRagIndexingItemResult item : items) {
            if (item.indexed()) {
                indexed++;
            }
        }
        // The totals are cosmetic here — the rollback service reads itemResults, not the totals.
        // In SMALL_BATCH mode the totals cap indexed/embeddingRows/ragExpected at MAX_SMALL_BATCH (3),
        // so clamp those three fields; itemResults can still legitimately carry more entries (e.g. the
        // "4 eligible" scenario) because they are what actually drives the batch.
        int cappedIndexed = Math.min(indexed, AtFaqRagIndexingTotals.MAX_SMALL_BATCH);
        AtFaqRagIndexingTotals totals = new AtFaqRagIndexingTotals(
                AtFaqRagIndexingMode.SMALL_BATCH_TEST_ISOLATED,
                items.length, items.length, cappedIndexed, 0, 0, cappedIndexed, cappedIndexed);
        return new AtFaqRagIndexingResult(
                "SYNTH", FIXED_INSTANT, "test", AtFaqRagIndexingMode.SMALL_BATCH_TEST_ISOLATED,
                totals, List.of(items), List.of(), List.of(), List.of(), items.length, cappedIndexed);
    }

    private long embeddingRowsFor(UUID qaId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                Long.class, qaId.toString());
        return n != null ? n : 0L;
    }
}
