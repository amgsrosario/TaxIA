package com.knowledgeflow.ingestion.atfaq.batch;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Effective governed indexing of a <em>small batch</em> of published Q&amp;A in an isolated database
 * — Bloco E, E9B.
 *
 * <p>Frase-mestra: "Indexar vários não é escalar livremente. É provar que o lote obedece aos mesmos
 * guardas do caso único."
 *
 * <p>Builds published, VALIDATED, LOW-risk Q&amp;A through the real domain API, wraps them in a
 * governed publication result, and drives {@link AtFaqGovernedRagIndexingService#indexSmallPublishedBatch}
 * with the <b>real</b> {@link KnowledgeQaEmbeddingIndexerImpl} SQL fed by a deterministic 768-dim
 * in-test embedding. It proves that a small batch (more than one, at most three):
 * <ul>
 *   <li>indexes exactly the eligible published items, one embedding row each;</li>
 *   <li>respects the hard batch ceiling (3) and the {@code maxItems} limit — deferring, never dropping;</li>
 *   <li>obeys the same per-Q&amp;A guards as the single case (not published / IMPORTED / wrong org /
 *       risk != LOW are blocked);</li>
 *   <li>is retrievable by {@link RagSearchService} (set membership, not exact order — the deterministic
 *       embedding gives every case similarity ≈ 1.0);</li>
 *   <li>is idempotent (re-running the batch keeps exactly N rows);</li>
 *   <li>never calls an external provider and never touches the real pilot base.</li>
 * </ul>
 *
 * <p>Run: mvn verify -Ppgtest -Dit.test=AtFaqGovernedRagBatchIndexingServiceIT
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AtFaqGovernedRagBatchIndexingServiceIT {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-06T10:15:30Z");
    private static final String PUBLISHED_BY = "taxia-governed-publication-test";
    private static final String INDEXED_BY = "taxia-governed-batch-indexing-test";
    private static final String SOURCE_SYSTEM = "at-faq-governed-batch-e9b";
    private static final int DIM = 768;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired KnowledgeSourceReferenceRepository sourceRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired JdbcTemplate jdbc;

    private Organization org;
    private Organization otherOrg;
    private AtFaqGovernedRagIndexingService ragService;
    private RagSearchService ragSearch;

    // Shared across ordered tests (the main batch of three).
    private UUID mainA;
    private UUID mainB;
    private UUID mainC;
    private AtFaqRagIndexingResult mainBatchResult;

    @BeforeAll
    void setUp() {
        org = organizationRepository.save(new Organization("Org — Indexação E9B", null));
        otherOrg = organizationRepository.save(new Organization("Org — Outra E9B", null));

        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        EmbeddingService embedding = fixedEmbedding();
        ragService = new AtFaqGovernedRagIndexingService(
                new KnowledgeQaEmbeddingIndexerImpl(embedding, jdbc),
                qaRepository, sourceRepository, jdbc, clock);
        ragSearch = new RagSearchService(
                embedding, jdbc,
                new EmbeddingProperties(null, 5, null, null, null, null, null),
                new KnowledgeFlowMetrics(new SimpleMeterRegistry()));

        // The main small batch: three published, VALIDATED, LOW-risk, RAG-eligible Q&A.
        mainA = newPublishedQa("AT-FAQ-2001", KnowledgeRiskLevel.LOW, org).getId();
        mainB = newPublishedQa("AT-FAQ-2002", KnowledgeRiskLevel.LOW, org).getId();
        mainC = newPublishedQa("AT-FAQ-2003", KnowledgeRiskLevel.LOW, org).getId();
    }

    private AtFaqGovernedPublicationExecutionResult mainBatchPublication() {
        return wrapPub(
                itemResult("AT-FAQ-2001", mainA, true),
                itemResult("AT-FAQ-2002", mainB, true),
                itemResult("AT-FAQ-2003", mainC, true));
    }

    // =========================================================================
    // TC-01 — pré-condição: três publicados, ainda zero embeddings
    // =========================================================================

    @Test @Order(1)
    @DisplayName("TC-01: três Q&A publicados e elegíveis, mas ainda sem qualquer embedding")
    void preconditionThreePublishedButNotIndexed() {
        assertThat(embeddingRowsFor(mainA)).isZero();
        assertThat(embeddingRowsFor(mainB)).isZero();
        assertThat(embeddingRowsFor(mainC)).isZero();
    }

    // =========================================================================
    // TC-02 — indexa exactamente o lote pequeno de três
    // =========================================================================

    @Test @Order(2)
    @DisplayName("TC-02: indexa exactamente os três Q&A publicados (maxItems=3)")
    void indexesExactlyTheSmallBatch() {
        mainBatchResult = ragService.indexSmallPublishedBatch(mainBatchPublication(), org, INDEXED_BY, 3);

        assertThat(mainBatchResult.mode()).isEqualTo(AtFaqRagIndexingMode.SMALL_BATCH_TEST_ISOLATED);
        assertThat(mainBatchResult.indexedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(mainBatchResult.indexedBy()).isEqualTo(INDEXED_BY);
        assertThat(mainBatchResult.blockingErrors()).isEmpty();
        assertThat(mainBatchResult.requestedMaxItems()).isEqualTo(3);
        assertThat(mainBatchResult.effectiveMaxItems()).isEqualTo(3);

        AtFaqRagIndexingTotals t = mainBatchResult.totals();
        assertThat(t.mode()).isEqualTo(AtFaqRagIndexingMode.SMALL_BATCH_TEST_ISOLATED);
        assertThat(t.totalPublishedItems()).isEqualTo(3);
        assertThat(t.eligibleForIndexing()).isEqualTo(3);
        assertThat(t.indexed()).isEqualTo(3);
        assertThat(t.skipped()).isZero();
        assertThat(t.blocked()).isZero();
        assertThat(t.embeddingRows()).isEqualTo(3);
        assertThat(t.ragExpected()).isEqualTo(3);

        assertThat(mainBatchResult.itemResults()).hasSize(3);
        assertThat(mainBatchResult.itemResults())
                .allMatch(AtFaqRagIndexingItemResult::indexed)
                .allMatch(AtFaqRagIndexingItemResult::embeddingPresent)
                .allMatch(AtFaqRagIndexingItemResult::ragExpectedToRetrieve)
                .allMatch(i -> i.embeddingRows() == 1);
    }

    // =========================================================================
    // TC-03 — limite absoluto: uma linha por Q&A, três no total
    // =========================================================================

    @Test @Order(3)
    @DisplayName("TC-03: knowledge_qa_embeddings tem exactamente 1 linha por Q&A (3 no total)")
    void exactlyOneEmbeddingRowPerQa() {
        assertThat(embeddingRowsFor(mainA)).isEqualTo(1);
        assertThat(embeddingRowsFor(mainB)).isEqualTo(1);
        assertThat(embeddingRowsFor(mainC)).isEqualTo(1);
        assertThat(countEmbeddingsForOrg(org)).isEqualTo(3);
    }

    // =========================================================================
    // TC-04 — o RAG recupera os três (pertença ao conjunto, não ordem exacta)
    // =========================================================================

    @Test @Order(4)
    @DisplayName("TC-04: RagSearchService recupera os três Q&A indexados (pertença ao conjunto)")
    void ragRetrievesAllThreeIndexed() {
        List<RagSearchService.RetrievedCase> results =
                ragSearch.findSimilar(org.getId(), "Qual o enquadramento em IVA desta operação?");

        List<UUID> retrievedIds = results.stream()
                .map(RagSearchService.RetrievedCase::sourceQaId)
                .toList();

        // Set membership, not order: the deterministic embedding gives every case similarity ≈ 1.0.
        assertThat(retrievedIds).contains(mainA, mainB, mainC);
        assertThat(results).filteredOn(r -> r.sourceQaId() != null)
                .allMatch(r -> r.sourceKind() == RagSearchService.SourceKind.KNOWLEDGE_QA)
                .allMatch(r -> r.similarity() > 0.99);
    }

    // =========================================================================
    // TC-05 — idempotência: reindexar o lote mantém exactamente N linhas
    // =========================================================================

    @Test @Order(5)
    @DisplayName("TC-05: reindexar o mesmo lote faz upsert — continua 1 linha por Q&A")
    void reindexingBatchIsIdempotent() {
        AtFaqRagIndexingResult second =
                ragService.indexSmallPublishedBatch(mainBatchPublication(), org, INDEXED_BY, 3);

        assertThat(second.blockingErrors()).isEmpty();
        assertThat(second.totals().indexed()).isEqualTo(3);
        assertThat(second.totals().embeddingRows()).isEqualTo(3);
        assertThat(embeddingRowsFor(mainA)).isEqualTo(1);
        assertThat(embeddingRowsFor(mainB)).isEqualTo(1);
        assertThat(embeddingRowsFor(mainC)).isEqualTo(1);
        assertThat(countEmbeddingsForOrg(org)).isEqualTo(3);
    }

    // =========================================================================
    // TC-06 — limite do lote: 4 elegíveis + maxItems=3 → indexa 3, difere 1
    // =========================================================================

    @Test @Order(6)
    @DisplayName("TC-06: 4 elegíveis com maxItems=3 indexa 3 e difere 1 (respeita o limite)")
    void batchLimitDefersTheExcess() {
        KnowledgeQuestionAnswer q1 = newPublishedQa("AT-FAQ-2101", KnowledgeRiskLevel.LOW, org);
        KnowledgeQuestionAnswer q2 = newPublishedQa("AT-FAQ-2102", KnowledgeRiskLevel.LOW, org);
        KnowledgeQuestionAnswer q3 = newPublishedQa("AT-FAQ-2103", KnowledgeRiskLevel.LOW, org);
        KnowledgeQuestionAnswer q4 = newPublishedQa("AT-FAQ-2104", KnowledgeRiskLevel.LOW, org);

        AtFaqRagIndexingResult run = ragService.indexSmallPublishedBatch(
                wrapPub(itemResult("AT-FAQ-2101", q1.getId(), true),
                        itemResult("AT-FAQ-2102", q2.getId(), true),
                        itemResult("AT-FAQ-2103", q3.getId(), true),
                        itemResult("AT-FAQ-2104", q4.getId(), true)),
                org, INDEXED_BY, 3);

        assertThat(run.totals().totalPublishedItems()).isEqualTo(4);
        assertThat(run.totals().eligibleForIndexing()).isEqualTo(4);
        assertThat(run.totals().indexed()).isEqualTo(3);
        assertThat(run.totals().skipped()).isEqualTo(1);
        assertThat(run.totals().blocked()).isZero();
        assertThat(run.totals().embeddingRows()).isEqualTo(3);
        assertThat(run.globalWarnings()).anyMatch(w -> w.contains("Limite de lote pequeno"));

        // Exactly three of the four carry an embedding; the fourth (beyond the limit) does not.
        assertThat(embeddingRowsFor(q1.getId())).isEqualTo(1);
        assertThat(embeddingRowsFor(q2.getId())).isEqualTo(1);
        assertThat(embeddingRowsFor(q3.getId())).isEqualTo(1);
        assertThat(embeddingRowsFor(q4.getId())).isZero();

        AtFaqRagIndexingItemResult deferred = run.itemResults().stream()
                .filter(i -> q4.getId().equals(i.knowledgeQaId()))
                .findFirst().orElseThrow();
        assertThat(deferred.eligibleForIndexing()).isTrue(); // eligible, only deferred by the limit
        assertThat(deferred.indexed()).isFalse();
        assertThat(deferred.warnings()).anyMatch(w -> w.contains("diferido pelo limite"));
    }

    // =========================================================================
    // TC-07 — configuração inválida: maxItems < 2 é recusado (use o fluxo single)
    // =========================================================================

    @Test @Order(7)
    @DisplayName("TC-07: maxItems=1 é recusado como configuração inválida — nada é indexado")
    void maxItemsBelowTwoIsRejected() {
        KnowledgeQuestionAnswer q1 = newPublishedQa("AT-FAQ-2201", KnowledgeRiskLevel.LOW, org);
        KnowledgeQuestionAnswer q2 = newPublishedQa("AT-FAQ-2202", KnowledgeRiskLevel.LOW, org);

        AtFaqRagIndexingResult run = ragService.indexSmallPublishedBatch(
                wrapPub(itemResult("AT-FAQ-2201", q1.getId(), true),
                        itemResult("AT-FAQ-2202", q2.getId(), true)),
                org, INDEXED_BY, 1);

        assertThat(run.mode()).isEqualTo(AtFaqRagIndexingMode.SMALL_BATCH_TEST_ISOLATED);
        assertThat(run.blockingErrors()).anyMatch(e -> e.contains("maxItems >= 2"));
        assertThat(run.totals().indexed()).isZero();
        assertThat(run.requestedMaxItems()).isEqualTo(1);
        assertThat(run.effectiveMaxItems()).isZero();
        assertThat(embeddingRowsFor(q1.getId())).isZero();
        assertThat(embeddingRowsFor(q2.getId())).isZero();
    }

    // =========================================================================
    // TC-08 — configuração inválida: maxItems > 3 é recusado — nada é indexado
    // =========================================================================

    @Test @Order(8)
    @DisplayName("TC-08: maxItems=4 excede o teto do lote pequeno — recusado, nada indexado")
    void maxItemsAboveCeilingIsRejected() {
        KnowledgeQuestionAnswer q1 = newPublishedQa("AT-FAQ-2301", KnowledgeRiskLevel.LOW, org);
        KnowledgeQuestionAnswer q2 = newPublishedQa("AT-FAQ-2302", KnowledgeRiskLevel.LOW, org);

        AtFaqRagIndexingResult run = ragService.indexSmallPublishedBatch(
                wrapPub(itemResult("AT-FAQ-2301", q1.getId(), true),
                        itemResult("AT-FAQ-2302", q2.getId(), true)),
                org, INDEXED_BY, 4);

        assertThat(run.blockingErrors()).anyMatch(e -> e.contains("não pode exceder 3"));
        assertThat(run.totals().indexed()).isZero();
        assertThat(run.requestedMaxItems()).isEqualTo(4);
        assertThat(run.effectiveMaxItems()).isEqualTo(3);
        assertThat(embeddingRowsFor(q1.getId())).isZero();
        assertThat(embeddingRowsFor(q2.getId())).isZero();
    }

    // =========================================================================
    // TC-09 — mesmos guardas do caso único: só os elegíveis do lote são indexados
    // =========================================================================

    @Test @Order(9)
    @DisplayName("TC-09: lote misto — indexa só os 2 LOW publicados; bloqueia IMPORTED/HIGH/outra-org")
    void batchObeysSameGuardsAsSingle() {
        KnowledgeQuestionAnswer low1 = newPublishedQa("AT-FAQ-2401", KnowledgeRiskLevel.LOW, org);
        KnowledgeQuestionAnswer low2 = newPublishedQa("AT-FAQ-2402", KnowledgeRiskLevel.LOW, org);
        KnowledgeQuestionAnswer imported = newImportedQa("AT-FAQ-2403", KnowledgeRiskLevel.LOW, org);
        addSource(imported, "Artigo 41.º do CIVA");
        KnowledgeQuestionAnswer high = newPublishedQa("AT-FAQ-2404", KnowledgeRiskLevel.HIGH, org);
        KnowledgeQuestionAnswer foreign = newPublishedQa("AT-FAQ-2405", KnowledgeRiskLevel.LOW, otherOrg);

        AtFaqRagIndexingResult run = ragService.indexSmallPublishedBatch(
                wrapPub(itemResult("AT-FAQ-2401", low1.getId(), true),
                        itemResult("AT-FAQ-2402", low2.getId(), true),
                        itemResult("AT-FAQ-2403", imported.getId(), true),
                        itemResult("AT-FAQ-2404", high.getId(), true),
                        itemResult("AT-FAQ-2405", foreign.getId(), true)),
                org, INDEXED_BY, 3);

        assertThat(run.totals().totalPublishedItems()).isEqualTo(5);
        assertThat(run.totals().eligibleForIndexing()).isEqualTo(2);
        assertThat(run.totals().indexed()).isEqualTo(2);
        assertThat(run.totals().blocked()).isEqualTo(3);
        assertThat(run.totals().skipped()).isZero();
        assertThat(run.totals().embeddingRows()).isEqualTo(2);

        assertThat(embeddingRowsFor(low1.getId())).isEqualTo(1);
        assertThat(embeddingRowsFor(low2.getId())).isEqualTo(1);
        assertThat(embeddingRowsFor(imported.getId())).isZero();
        assertThat(embeddingRowsFor(high.getId())).isZero();
        assertThat(embeddingRowsFor(foreign.getId())).isZero();

        assertThat(blockingReasonsFor(run, imported.getId())).anyMatch(r -> r.contains("não está publicada"));
        assertThat(blockingReasonsFor(run, high.getId())).anyMatch(r -> r.contains("Risco != LOW"));
        assertThat(blockingReasonsFor(run, foreign.getId()))
                .anyMatch(r -> r.contains("não pertence à organização"));
    }

    // =========================================================================
    // TC-10 — nenhum item publicado → nada a indexar
    // =========================================================================

    @Test @Order(10)
    @DisplayName("TC-10: lote sem qualquer item publicado não indexa nada")
    void noPublishedItemIndexesNothing() {
        AtFaqGovernedPublicationExecutionResult nonesuch = wrapPub(
                itemResult("AT-FAQ-2501", UUID.randomUUID(), false),
                itemResult("AT-FAQ-2502", UUID.randomUUID(), false));

        AtFaqRagIndexingResult run = ragService.indexSmallPublishedBatch(nonesuch, org, INDEXED_BY, 3);

        assertThat(run.totals().totalPublishedItems()).isZero();
        assertThat(run.totals().indexed()).isZero();
        assertThat(run.totals().embeddingRows()).isZero();
        assertThat(run.blockingErrors()).isNotEmpty();
    }

    // =========================================================================
    // TC-11 — higiene do relatório: sem vector bruto/HTML/prompts/chunks
    // =========================================================================

    @Test @Order(11)
    @DisplayName("TC-11: relatório do lote não contém vector bruto, HTML, prompts nem chunks")
    void reportContainsNoRawVectorHtmlPromptsOrChunks() {
        List<String> texts = new ArrayList<>();
        texts.addAll(mainBatchResult.globalWarnings());
        texts.addAll(mainBatchResult.blockingErrors());
        texts.addAll(mainBatchResult.nextActions());
        for (AtFaqRagIndexingItemResult item : mainBatchResult.itemResults()) {
            if (item.normalizedQuestion() != null) texts.add(item.normalizedQuestion());
            texts.addAll(item.warnings());
            texts.addAll(item.blockingReasons());
            texts.addAll(item.nextActions());
        }
        for (String s : texts) {
            if (s != null) {
                assertThat(s).doesNotContain("<").doesNotContain("```").doesNotContain("[0.");
                assertThat(s.toLowerCase()).doesNotContain("prompt").doesNotContain("chunk");
            }
        }
    }

    // =========================================================================
    // TC-12 — zero chamadas externas: datasource ligado ao container local
    // =========================================================================

    @Test @Order(12)
    @DisplayName("TC-12: zero chamadas externas — datasource ligado ao container local")
    void zeroExternalCalls() {
        assertThat(postgres.getJdbcUrl()).startsWith("jdbc:postgresql://");
        assertThat(postgres.getHost()).isIn("localhost", "127.0.0.1");
    }

    // =========================================================================
    // TC-13 — regressão: o fluxo single continua single (cap estrutural intacto)
    // =========================================================================

    @Test @Order(13)
    @DisplayName("TC-13: indexSinglePublishedQa continua a indexar 1 e a diferir o resto (E9A intacto)")
    void singleFlowStaysSingle() {
        KnowledgeQuestionAnswer q1 = newPublishedQa("AT-FAQ-2601", KnowledgeRiskLevel.LOW, org);
        KnowledgeQuestionAnswer q2 = newPublishedQa("AT-FAQ-2602", KnowledgeRiskLevel.LOW, org);

        AtFaqRagIndexingResult run = ragService.indexSinglePublishedQa(
                wrapPub(itemResult("AT-FAQ-2601", q1.getId(), true),
                        itemResult("AT-FAQ-2602", q2.getId(), true)),
                org, INDEXED_BY);

        assertThat(run.mode()).isEqualTo(AtFaqRagIndexingMode.TEST_ISOLATED_SINGLE_QA);
        assertThat(run.totals().indexed()).isEqualTo(1);
        assertThat(run.totals().skipped()).isEqualTo(1);
        assertThat(run.requestedMaxItems()).isEqualTo(1);
        assertThat(embeddingRowsFor(q1.getId())).isEqualTo(1);
        assertThat(embeddingRowsFor(q2.getId())).isZero();
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

    private void addSource(KnowledgeQuestionAnswer qa, String legalReference) {
        KnowledgeSourceReference source = new KnowledgeSourceReference(
                qa, KnowledgeSourceType.LEGISLATION, "Fonte oficial " + qa.getExternalKey());
        source.update(KnowledgeSourceType.LEGISLATION, "Fonte oficial", legalReference,
                null, null, null, null, null, null);
        sourceRepository.save(source);
    }

    private static AtFaqGovernedPublicationExecutionItemResult itemResult(
            String externalId, UUID qaId, boolean published) {
        return new AtFaqGovernedPublicationExecutionItemResult(
                externalId, qaId,
                true,       // eligibleForGovernedPublication
                published,  // validated
                published,  // published
                false,      // indexed
                published ? PUBLISHED_BY : null,
                published ? FIXED_INSTANT : null,
                published ? KnowledgeCurationStatus.VALIDATED : KnowledgeCurationStatus.IMPORTED,
                published,  // eligibleForRagByEntityRules (re-checked by the service anyway)
                false,      // embeddingPresent
                false,      // ragExpectedToRetrieve
                List.of(), List.of(), List.of(), List.of());
    }

    private AtFaqGovernedPublicationExecutionResult wrapPub(
            AtFaqGovernedPublicationExecutionItemResult... items) {
        int published = 0;
        for (AtFaqGovernedPublicationExecutionItemResult i : items) {
            if (i.published()) published++;
        }
        return new AtFaqGovernedPublicationExecutionResult(
                "SYNTH-E9B", FIXED_INSTANT, "test",
                AtFaqGovernedPublicationExecutionMode.TEST_ISOLATED_WITH_STUB_INDEXER,
                new AtFaqGovernedPublicationExecutionTotals(
                        items.length, published, published, published, 0, 0, 0, 0, 0, 0),
                List.of(items), List.of(), List.of(), List.of());
    }

    private static List<String> blockingReasonsFor(AtFaqRagIndexingResult run, UUID qaId) {
        return run.itemResults().stream()
                .filter(i -> qaId.equals(i.knowledgeQaId()))
                .findFirst().orElseThrow()
                .blockingReasons();
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
