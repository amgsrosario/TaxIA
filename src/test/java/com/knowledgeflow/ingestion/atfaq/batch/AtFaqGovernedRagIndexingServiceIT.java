package com.knowledgeflow.ingestion.atfaq.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.common.observability.KnowledgeFlowMetrics;
import com.knowledgeflow.ingestion.atfaq.AtFaqNormalizer;
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
 * Effective governed indexing of a single published Q&A in an isolated database — Bloco E, E9A.
 *
 * <p>Frase-mestra: "Indexar não é escalar. É dar voz controlada a um único conhecimento publicado."
 *
 * <p>Runs the full pipeline E4→…→E8B.3 (the real {@link AtFaqGovernedPublicationExecutor}, which
 * publishes exactly one clean LOW-risk item under the {@code pgtest} stub indexer, leaving zero
 * embeddings), then drives {@link AtFaqGovernedRagIndexingService} to give that single published
 * Q&A <em>controlled voice</em>: the service is constructed here with the <b>real</b>
 * {@link KnowledgeQaEmbeddingIndexerImpl} SQL fed by a deterministic 768-dim in-test embedding
 * (the same pattern as {@code KnowledgeQaEmbeddingIndexerPostgresIT}). So E9A writes exactly one
 * row in {@code knowledge_qa_embeddings} and a real {@link RagSearchService} — built with the same
 * deterministic embedding — retrieves it for a semantically compatible question.
 *
 * <p>This proves the genuine RAG/pgvector cycle without the real embedding model, without any
 * external call, and without ever touching the real pilot base. It also proves idempotency (upsert
 * keeps exactly one row) and every negative guard (not published, not VALIDATED/not published in DB,
 * wrong organization, risk != LOW) and the single-Q&A (non-batch) policy.
 *
 * <p>Run: mvn verify -Ppgtest -Dit.test=AtFaqGovernedRagIndexingServiceIT
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AtFaqGovernedRagIndexingServiceIT {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-06T10:15:30Z");
    private static final String PUBLISHED_BY = "taxia-governed-publication-test";
    private static final String INDEXED_BY = "taxia-governed-indexing-test";
    private static final String SOURCE_SYSTEM = "at-faq-governed-batch";
    private static final int DIM = 768;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired KnowledgeSourceReferenceRepository sourceRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired KnowledgeQuestionAnswerPublicationService publicationService;
    @Autowired JdbcTemplate jdbc;

    private Organization org;
    private Organization otherOrg;
    private AtFaqGovernedRagIndexingService ragService;
    private RagSearchService ragSearch;

    private AtFaqGovernedPublicationExecutionResult firstRun; // the E8B.3 publication (AT-FAQ-1001)
    private UUID cleanQaId;

    // Shared across ordered tests.
    private AtFaqRagIndexingResult mainIndexResult;

    @BeforeAll
    void setUp() {
        org = organizationRepository.save(new Organization("Org — Indexação E9A", null));
        otherOrg = organizationRepository.save(new Organization("Org — Outra E9A", null));

        // The real publish(...) records an audit event; Postgres enforces the audit → users FK.
        provisionGovernedActor(AtFaqGovernedPublicationExecutor.deterministicActor(PUBLISHED_BY));

        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

        // ---- Full governed pipeline E4 → E8B.3 (produces the real publication result) ----------
        AtFaqNormalizer normalizer = new AtFaqNormalizer();
        AtFaqControlledBatchService batchService = new AtFaqControlledBatchService(normalizer, clock);
        AtFaqPreCurationService preCurationService =
                new AtFaqPreCurationService(batchService, normalizer, clock);
        AtFaqReviewService reviewService = new AtFaqReviewService(clock);
        AtFaqGovernedPublicationPlanService planService =
                new AtFaqGovernedPublicationPlanService(clock);
        AtFaqGovernedMaterializationService materializationService =
                new AtFaqGovernedMaterializationService(clock, new AtFaqKnowledgeQaDraftAssembler());
        AtFaqGovernedPublicationDryRunExecutor dryRunExecutor =
                new AtFaqGovernedPublicationDryRunExecutor(clock);
        AtFaqGovernedDraftPersistenceService persistenceService =
                new AtFaqGovernedDraftPersistenceService(qaRepository, sourceRepository, clock);
        AtFaqGovernedPublicationExecutor executor = new AtFaqGovernedPublicationExecutor(
                publicationService, qaRepository, sourceRepository, clock);

        AtFaqPreCurationResult preCuration =
                preCurationService.preCurate(ControlledBatchFixtures.sixItemBatch());
        AtFaqReviewResult review = reviewService.review(preCuration, List.of(
                AtFaqReviewDecision.of("AT-FAQ-1001", AtFaqReviewDecisionType.ACCEPT_AUTO_CONTROLLED_CANDIDATE, "test-reviewer", "Base sólida."),
                AtFaqReviewDecision.of("AT-FAQ-1002", AtFaqReviewDecisionType.SEND_TO_ASSISTED_REVIEW, "test-reviewer", "Falta base legal."),
                AtFaqReviewDecision.of("AT-FAQ-1003", AtFaqReviewDecisionType.REJECT, "test-reviewer", "Duplicado."),
                AtFaqReviewDecision.of("AT-FAQ-1004", AtFaqReviewDecisionType.REQUIRE_MANUAL_REVIEW, "test-reviewer", "Conflito."),
                AtFaqReviewDecision.of("AT-FAQ-1005", AtFaqReviewDecisionType.REQUIRE_MANUAL_REVIEW, "test-reviewer", "Risco alto."),
                AtFaqReviewDecision.of("AT-FAQ-1006", AtFaqReviewDecisionType.REJECT, "test-reviewer", "Sem resposta técnica.")),
                "test-reviewer");
        AtFaqGovernedPublicationPlan plan = planService.plan(preCuration, review, "test-planner");
        AtFaqMaterializationResult materializationResult =
                materializationService.materializeDrafts(plan, "test-materializer");
        AtFaqPublicationDryRunReport dryRunReport =
                dryRunExecutor.dryRun(materializationResult, "test-executor");
        AtFaqDraftPersistenceResult persistenceResult = persistenceService.persistDrafts(
                materializationResult, dryRunReport, org, "test-persister");

        firstRun = executor.publishGoverned(persistenceResult, org, PUBLISHED_BY);
        cleanQaId = loadCleanQa().getId();

        // ---- E9A wiring: real indexer SQL + deterministic embedding (no external calls) ---------
        EmbeddingService embedding = fixedEmbedding();
        ragService = new AtFaqGovernedRagIndexingService(
                new KnowledgeQaEmbeddingIndexerImpl(embedding, jdbc),
                qaRepository, sourceRepository, jdbc, clock);
        ragSearch = new RagSearchService(
                embedding, jdbc,
                new EmbeddingProperties(null, 5, null, null, null, null, null),
                new KnowledgeFlowMetrics(new SimpleMeterRegistry()));
    }

    // =========================================================================
    // TC-01 — pré-condição: publicado em E8B.3, mas ainda sem qualquer embedding
    // =========================================================================

    @Test @Order(1)
    @DisplayName("TC-01: após E8B.3 há 1 publicado e 0 embeddings (indexação ainda por fazer)")
    void preconditionPublishedButNotIndexed() {
        assertThat(firstRun.totals().published()).isEqualTo(1);
        assertThat(countEmbeddingsForOrg()).isZero();
        assertThat(embeddingRowsFor(cleanQaId)).isZero();
    }

    // =========================================================================
    // TC-02 — indexa exactamente 1 Q&A publicado
    // =========================================================================

    @Test @Order(2)
    @DisplayName("TC-02: indexa exactamente o único Q&A publicado (AT-FAQ-1001)")
    void indexesExactlyOnePublishedQa() {
        mainIndexResult = ragService.indexSinglePublishedQa(firstRun, org, INDEXED_BY);

        assertThat(mainIndexResult.mode()).isEqualTo(AtFaqRagIndexingMode.TEST_ISOLATED_SINGLE_QA);
        assertThat(mainIndexResult.indexedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(mainIndexResult.indexedBy()).isEqualTo(INDEXED_BY);
        assertThat(mainIndexResult.blockingErrors()).isEmpty();

        AtFaqRagIndexingTotals t = mainIndexResult.totals();
        assertThat(t.totalPublishedItems()).isEqualTo(1);
        assertThat(t.eligibleForIndexing()).isEqualTo(1);
        assertThat(t.indexed()).isEqualTo(1);
        assertThat(t.skipped()).isZero();
        assertThat(t.blocked()).isZero();
        assertThat(t.embeddingRows()).isEqualTo(1);
        assertThat(t.ragExpected()).isEqualTo(1);

        assertThat(mainIndexResult.itemResults()).hasSize(1);
        AtFaqRagIndexingItemResult item = mainIndexResult.itemResults().get(0);
        assertThat(item.externalId()).isEqualTo("AT-FAQ-1001");
        assertThat(item.knowledgeQaId()).isEqualTo(cleanQaId);
        assertThat(item.eligibleForIndexing()).isTrue();
        assertThat(item.indexed()).isTrue();
        assertThat(item.embeddingPresent()).isTrue();
        assertThat(item.ragExpectedToRetrieve()).isTrue();
        assertThat(item.embeddingRows()).isEqualTo(1);
    }

    // =========================================================================
    // TC-03 — limite absoluto: exactamente 1 linha de embedding para esse Q&A
    // =========================================================================

    @Test @Order(3)
    @DisplayName("TC-03: knowledge_qa_embeddings tem exactamente 1 linha para o Q&A publicado")
    void exactlyOneEmbeddingRowForThatQa() {
        assertThat(embeddingRowsFor(cleanQaId)).isEqualTo(1);
        // Neste ponto do teste principal, esse é o único embedding da organização.
        assertThat(countEmbeddingsForOrg()).isEqualTo(1);
    }

    // =========================================================================
    // TC-04 — o RAG recupera o Q&A para uma pergunta semanticamente compatível
    // =========================================================================

    @Test @Order(4)
    @DisplayName("TC-04: RagSearchService recupera o Q&A indexado (KNOWLEDGE_QA, similaridade ≈ 1.0)")
    void ragRetrievesTheIndexedQa() {
        List<RagSearchService.RetrievedCase> results =
                ragSearch.findSimilar(org.getId(), "Qual o limiar do volume de negócios para IVA mensal?");

        RagSearchService.RetrievedCase hit = results.stream()
                .filter(r -> cleanQaId.equals(r.sourceQaId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Q&A indexado não foi recuperado pelo RAG"));

        assertThat(hit.sourceKind()).isEqualTo(RagSearchService.SourceKind.KNOWLEDGE_QA);
        assertThat(hit.similarity()).isGreaterThan(0.99);
    }

    // =========================================================================
    // TC-05 — idempotência: reindexar não duplica (upsert mantém 1 linha)
    // =========================================================================

    @Test @Order(5)
    @DisplayName("TC-05: reindexar o mesmo Q&A faz upsert — continua exactamente 1 linha")
    void reindexingIsIdempotent() {
        AtFaqRagIndexingResult second = ragService.indexSinglePublishedQa(firstRun, org, INDEXED_BY);

        assertThat(second.blockingErrors()).isEmpty();
        assertThat(second.totals().indexed()).isEqualTo(1);
        assertThat(second.totals().embeddingRows()).isEqualTo(1);
        assertThat(embeddingRowsFor(cleanQaId)).isEqualTo(1);
        assertThat(countEmbeddingsForOrg()).isEqualTo(1);
    }

    // =========================================================================
    // TC-06 — guarda negativa: nenhum item publicado → nada a indexar
    // =========================================================================

    @Test @Order(6)
    @DisplayName("TC-06: resultado de publicação sem item publicado não indexa nada")
    void noPublishedItemIndexesNothing() {
        AtFaqGovernedPublicationExecutionResult nonesuch =
                wrapPub(itemResult("AT-FAQ-9001", UUID.randomUUID(), false));

        AtFaqRagIndexingResult run = ragService.indexSinglePublishedQa(nonesuch, org, INDEXED_BY);

        assertThat(run.totals().totalPublishedItems()).isZero();
        assertThat(run.totals().indexed()).isZero();
        assertThat(run.totals().embeddingRows()).isZero();
        assertThat(run.blockingErrors()).isNotEmpty();
    }

    // =========================================================================
    // TC-07 — guarda negativa: entidade não publicada/não VALIDATED (IMPORTED) → bloqueado
    // =========================================================================

    @Test @Order(7)
    @DisplayName("TC-07: item marcado publicado mas a entidade está IMPORTED/não publicada → bloqueado")
    void importedEntityIsBlocked() {
        KnowledgeQuestionAnswer qa = newImportedQa("AT-FAQ-9002", KnowledgeRiskLevel.LOW, org);
        addSource(qa, "Artigo 41.º do CIVA");

        AtFaqRagIndexingResult run = ragService.indexSinglePublishedQa(
                wrapPub(itemResult("AT-FAQ-9002", qa.getId(), true)), org, INDEXED_BY);

        assertThat(run.totals().indexed()).isZero();
        assertThat(run.totals().blocked()).isEqualTo(1);
        assertThat(run.itemResults().get(0).blockingReasons())
                .anyMatch(r -> r.contains("não está publicada"));
        assertThat(embeddingRowsFor(qa.getId())).isZero();
    }

    // =========================================================================
    // TC-08 — guarda negativa: organização errada → bloqueado
    // =========================================================================

    @Test @Order(8)
    @DisplayName("TC-08: Q&A publicado de outra organização é bloqueado (isolamento por organização)")
    void wrongOrganizationIsBlocked() {
        KnowledgeQuestionAnswer qa = newPublishedQa("AT-FAQ-9003", KnowledgeRiskLevel.LOW, org);

        // Pede-se indexação sob `otherOrg`, mas o Q&A pertence a `org`.
        AtFaqRagIndexingResult run = ragService.indexSinglePublishedQa(
                wrapPub(itemResult("AT-FAQ-9003", qa.getId(), true)), otherOrg, INDEXED_BY);

        assertThat(run.totals().indexed()).isZero();
        assertThat(run.totals().blocked()).isEqualTo(1);
        assertThat(run.itemResults().get(0).blockingReasons())
                .anyMatch(r -> r.contains("não pertence à organização"));
        assertThat(embeddingRowsFor(qa.getId())).isZero();
    }

    // =========================================================================
    // TC-09 — guarda negativa: risco != LOW → bloqueado (mesmo publicado e RAG-elegível)
    // =========================================================================

    @Test @Order(9)
    @DisplayName("TC-09: Q&A publicado de risco != LOW é bloqueado pela E9A (mais restrita que a entidade)")
    void nonLowRiskIsBlocked() {
        KnowledgeQuestionAnswer qa = newPublishedQa("AT-FAQ-9004", KnowledgeRiskLevel.HIGH, org);
        assertThat(qa.isEligibleForRag()).isTrue(); // a entidade aceitaria; a E9A não.

        AtFaqRagIndexingResult run = ragService.indexSinglePublishedQa(
                wrapPub(itemResult("AT-FAQ-9004", qa.getId(), true)), org, INDEXED_BY);

        assertThat(run.totals().indexed()).isZero();
        assertThat(run.totals().blocked()).isEqualTo(1);
        assertThat(run.itemResults().get(0).blockingReasons())
                .anyMatch(r -> r.contains("Risco != LOW"));
        assertThat(embeddingRowsFor(qa.getId())).isZero();
    }

    // =========================================================================
    // TC-10 — não é lote: com vários publicados, indexa 1 e difere os restantes
    // =========================================================================

    @Test @Order(10)
    @DisplayName("TC-10: com 2 Q&A publicados, E9A indexa apenas 1 e difere o outro para E9B")
    void multiplePublishedIndexesOnlyOne() {
        KnowledgeQuestionAnswer qaA = newPublishedQa("AT-FAQ-9005", KnowledgeRiskLevel.LOW, org);
        KnowledgeQuestionAnswer qaB = newPublishedQa("AT-FAQ-9006", KnowledgeRiskLevel.LOW, org);

        AtFaqRagIndexingResult run = ragService.indexSinglePublishedQa(
                wrapPub(itemResult("AT-FAQ-9005", qaA.getId(), true),
                        itemResult("AT-FAQ-9006", qaB.getId(), true)),
                org, INDEXED_BY);

        assertThat(run.totals().totalPublishedItems()).isEqualTo(2);
        assertThat(run.totals().indexed()).isEqualTo(1);
        assertThat(run.totals().skipped()).isEqualTo(1);
        assertThat(run.totals().embeddingRows()).isEqualTo(1);
        assertThat(run.globalWarnings()).anyMatch(w -> w.contains("single-Q&A"));

        assertThat(embeddingRowsFor(qaA.getId())).isEqualTo(1);
        assertThat(embeddingRowsFor(qaB.getId())).isZero();
    }

    // =========================================================================
    // TC-11 — higiene do relatório: sem vector bruto/HTML/prompts/chunks
    // =========================================================================

    @Test @Order(11)
    @DisplayName("TC-11: relatório não contém vector bruto, HTML, prompts nem chunks")
    void reportContainsNoRawVectorHtmlPromptsOrChunks() {
        List<String> texts = new ArrayList<>();
        texts.addAll(mainIndexResult.globalWarnings());
        texts.addAll(mainIndexResult.blockingErrors());
        texts.addAll(mainIndexResult.nextActions());
        for (AtFaqRagIndexingItemResult item : mainIndexResult.itemResults()) {
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

    private void provisionGovernedActor(UUID actorId) {
        jdbc.update(
                "INSERT INTO users (id, email, full_name, password_hash, status, created_at, updated_at) "
                        + "VALUES (?::uuid, ?, ?, ?, ?, now(), now())",
                actorId.toString(),
                "governed-publication@taxia.test",
                "TaxIA Governed Publication (service account)",
                "n/a",
                "ACTIVE");
    }

    private KnowledgeQuestionAnswer loadCleanQa() {
        return qaRepository.findByOrganizationIdAndSourceSystemAndExternalKey(
                        org.getId(), SOURCE_SYSTEM, "AT-FAQ-1001")
                .orElseThrow();
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
                published,  // eligibleForRagByEntityRules (re-checked by E9A anyway)
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
                "SYNTH", FIXED_INSTANT, "test",
                AtFaqGovernedPublicationExecutionMode.TEST_ISOLATED_WITH_STUB_INDEXER,
                new AtFaqGovernedPublicationExecutionTotals(
                        items.length, published, published, published, 0, 0, 0, 0, 0, 0),
                List.of(items), List.of(), List.of(), List.of());
    }

    private long embeddingRowsFor(UUID qaId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                Long.class, qaId.toString());
        return n != null ? n : 0L;
    }

    private long countEmbeddingsForOrg() {
        Long n = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM knowledge_qa_embeddings kqae
                JOIN knowledge_question_answers kqa ON kqa.id = kqae.knowledge_qa_id
                WHERE kqa.organization_id = ?::uuid
                """,
                Long.class, org.getId().toString());
        return n != null ? n : 0L;
    }
}
