package com.knowledgeflow.ingestion.atfaq.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.audit.service.AuditService;
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
 * Effective governed rollback of a single published/indexed Q&A in an isolated database — Bloco E,
 * E10A.
 *
 * <p>Frase-mestra: "Retirar voz não é apagar conhecimento. É neutralizar a recuperação preservando
 * rasto e motivo."
 *
 * <p>Runs the full pipeline E4→…→E8B.3 (the real {@link AtFaqGovernedPublicationExecutor}, which
 * publishes exactly one clean LOW-risk item under the {@code pgtest} stub indexer, leaving zero
 * embeddings), then E9A ({@link AtFaqGovernedRagIndexingService} built with the <b>real</b>
 * {@link KnowledgeQaEmbeddingIndexerImpl}) gives that single Q&A controlled voice — one row in
 * {@code knowledge_qa_embeddings}, retrievable by a real {@link RagSearchService}. It then drives
 * {@link AtFaqGovernedRollbackService} to roll that Q&A back through a
 * {@link KnowledgeQuestionAnswerPublicationService} constructed here with the <b>same real indexer</b>
 * (never the stub), so the real {@code unpublish(...)} genuinely deletes the embedding and clears the
 * publication. A RAG probe backed by the same deterministic embedding proves retrieval before and
 * its absence after.
 *
 * <p>This proves the genuine rollback cycle — despublicar + desindexar as distinct-but-coordinated
 * effects — without the real embedding model, without any external call, and without ever touching
 * the real pilot base. It also proves the mandatory reason, the preserved {@code KNOWLEDGE_QA_UNPUBLISHED}
 * audit trail, governed idempotency (a second rollback is skipped, not thrown), and every negative
 * guard (empty reason, wrong organization, not-indexed/not-published, published-without-embedding,
 * more than one eligible item).
 *
 * <p>Run: mvn verify -Ppgtest -Dit.test=AtFaqGovernedRollbackServiceIT
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AtFaqGovernedRollbackServiceIT {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-07T09:20:00Z");
    private static final String PUBLISHED_BY = "taxia-governed-publication-test";
    private static final String INDEXED_BY = "taxia-governed-indexing-test";
    private static final String ROLLED_BACK_BY = "taxia-governed-rollback-test";
    private static final String SOURCE_SYSTEM = "at-faq-governed-batch";
    private static final String REASON = "Alteração legislativa: resposta desatualizada; retirar do RAG.";
    private static final String RAG_QUERY = "Qual o limiar do volume de negócios para IVA mensal?";
    private static final int DIM = 768;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired KnowledgeSourceReferenceRepository sourceRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired KnowledgeQuestionAnswerPublicationService publicationService; // pgtest stub — used for publish
    @Autowired AuditService auditService;
    @Autowired JdbcTemplate jdbc;

    private Organization org;
    private Organization otherOrg;
    private AtFaqGovernedRollbackService rollbackService;
    private RagSearchService ragSearch;

    private UUID cleanQaId;
    private AtFaqRagIndexingResult mainIndexResult; // the E9A indexing of AT-FAQ-1001

    // Shared across ordered tests.
    private AtFaqRollbackResult mainRollback;

    @BeforeAll
    void setUp() {
        org = organizationRepository.save(new Organization("Org — Rollback E10A", null));
        otherOrg = organizationRepository.save(new Organization("Org — Outra E10A", null));

        // The real publish(...)/unpublish(...) record audit events; Postgres enforces audit → users FK.
        provisionActor(AtFaqGovernedPublicationExecutor.deterministicActor(PUBLISHED_BY),
                "governed-publication@taxia.test", "TaxIA Governed Publication (service account)");
        provisionActor(AtFaqGovernedRollbackService.deterministicActor(ROLLED_BACK_BY),
                "governed-rollback@taxia.test", "TaxIA Governed Rollback (service account)");

        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

        // ---- Full governed pipeline E4 → E8B.3 (autowired publicationService → stub, 0 embeddings) ----
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

        AtFaqGovernedPublicationExecutionResult firstRun =
                executor.publishGoverned(persistenceResult, org, PUBLISHED_BY);
        cleanQaId = loadCleanQa().getId();

        // ---- Shared real indexer + deterministic embedding (no external calls) ------------------
        EmbeddingService embedding = fixedEmbedding();
        KnowledgeQaEmbeddingIndexerImpl realIndexer = new KnowledgeQaEmbeddingIndexerImpl(embedding, jdbc);

        // E9A: give controlled voice to the single published Q&A through the real indexer.
        AtFaqGovernedRagIndexingService ragService = new AtFaqGovernedRagIndexingService(
                realIndexer, qaRepository, sourceRepository, jdbc, clock);
        mainIndexResult = ragService.indexSinglePublishedQa(firstRun, org, INDEXED_BY);

        // Real RAG search fed by the same deterministic embedding, and a probe over it.
        ragSearch = new RagSearchService(
                embedding, jdbc,
                new EmbeddingProperties(null, 5, null, null, null, null, null),
                new KnowledgeFlowMetrics(new SimpleMeterRegistry()));
        AtFaqRollbackRagProbe probe = (organizationId, qaId) ->
                ragSearch.findSimilar(organizationId, RAG_QUERY).stream()
                        .anyMatch(r -> qaId.equals(r.sourceQaId()));

        // E10A: rollback service wired with a PublicationService using the SAME real indexer,
        // so unpublish(...) truly deletes the embedding (the pgtest-autowired one would no-op).
        KnowledgeQuestionAnswerPublicationService realPublicationService =
                new KnowledgeQuestionAnswerPublicationService(
                        qaRepository, sourceRepository, realIndexer, auditService,
                        new KnowledgeFlowMetrics(new SimpleMeterRegistry()));
        rollbackService = new AtFaqGovernedRollbackService(
                realPublicationService, qaRepository, jdbc, probe, clock);
    }

    // =========================================================================
    // TC-01 — pré-condição: publicado + indexado + RAG recupera
    // =========================================================================

    @Test @Order(1)
    @DisplayName("TC-01: após E9A o Q&A está publicado, VALIDATED, com 1 embedding e o RAG recupera-o")
    void preconditionPublishedIndexedAndRetrieved() {
        assertThat(mainIndexResult.totals().indexed()).isEqualTo(1);

        KnowledgeQuestionAnswer qa = qaRepository.findById(cleanQaId).orElseThrow();
        assertThat(qa.isPublished()).isTrue();
        assertThat(qa.getPublishedAt()).isNotNull();
        assertThat(qa.getPublishedBy()).isNotNull();
        assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
        assertThat(embeddingRowsFor(cleanQaId)).isEqualTo(1);
        assertThat(ragRetrieves(cleanQaId)).isTrue();
    }

    // =========================================================================
    // TC-02 — rollback governado de exactamente 1 Q&A, com motivo
    // =========================================================================

    @Test @Order(2)
    @DisplayName("TC-02: rollback governado reverte exactamente o único Q&A indexado (com motivo)")
    void rollsBackExactlyOneIndexedQa() {
        mainRollback = rollbackService.rollbackSingleIndexedQa(mainIndexResult, org, ROLLED_BACK_BY, REASON);

        assertThat(mainRollback.mode()).isEqualTo(AtFaqRollbackMode.TEST_ISOLATED_SINGLE_QA);
        assertThat(mainRollback.rolledBackAt()).isEqualTo(FIXED_INSTANT);
        assertThat(mainRollback.rolledBackBy()).isEqualTo(ROLLED_BACK_BY);
        assertThat(mainRollback.blockingErrors()).isEmpty();

        AtFaqRollbackTotals t = mainRollback.totals();
        assertThat(t.totalIndexedItems()).isEqualTo(1);
        assertThat(t.eligibleForRollback()).isEqualTo(1);
        assertThat(t.rolledBack()).isEqualTo(1);
        assertThat(t.unpublished()).isEqualTo(1);
        assertThat(t.deindexed()).isEqualTo(1);
        assertThat(t.embeddingRowsBefore()).isEqualTo(1);
        assertThat(t.embeddingRowsAfter()).isZero();
        assertThat(t.ragRecoveredBefore()).isEqualTo(1);
        assertThat(t.ragRecoveredAfter()).isZero();
        assertThat(t.skipped()).isZero();
        assertThat(t.blocked()).isZero();

        assertThat(mainRollback.itemResults()).hasSize(1);
        AtFaqRollbackItemResult item = mainRollback.itemResults().get(0);
        assertThat(item.externalId()).isEqualTo("AT-FAQ-1001");
        assertThat(item.knowledgeQaId()).isEqualTo(cleanQaId);
        assertThat(item.rolledBack()).isTrue();
        assertThat(item.unpublished()).isTrue();
        assertThat(item.deindexed()).isTrue();
        assertThat(item.embeddingRemoved()).isTrue();
        assertThat(item.ragRecoveredBefore()).isTrue();
        assertThat(item.ragRecoveredAfter()).isFalse();
        assertThat(item.embeddingRowsBefore()).isEqualTo(1);
        assertThat(item.embeddingRowsAfter()).isZero();
        assertThat(item.publishedBefore()).isTrue();
        assertThat(item.publishedAfter()).isFalse();
    }

    // =========================================================================
    // TC-03 — limite absoluto: estado depois do rollback
    // =========================================================================

    @Test @Order(3)
    @DisplayName("TC-03: depois do rollback — publishedAt/By null, VALIDATED, 0 embeddings, RAG não recupera")
    void stateAfterRollback() {
        KnowledgeQuestionAnswer qa = qaRepository.findById(cleanQaId).orElseThrow();
        assertThat(qa.isPublished()).isFalse();
        assertThat(qa.getPublishedAt()).isNull();
        assertThat(qa.getPublishedBy()).isNull();
        // Rollback neutraliza a recuperação; não rebaixa a curadoria.
        assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
        assertThat(embeddingRowsFor(cleanQaId)).isZero();
        assertThat(ragRetrieves(cleanQaId)).isFalse();
    }

    // =========================================================================
    // TC-04 — auditoria existente preservada
    // =========================================================================

    @Test @Order(4)
    @DisplayName("TC-04: auditoria KNOWLEDGE_QA_UNPUBLISHED existe para o Q&A revertido")
    void unpublishAuditIsPreserved() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE entity_id = ?::uuid AND action = ?",
                Long.class, cleanQaId.toString(), "KNOWLEDGE_QA_UNPUBLISHED");
        assertThat(n).isNotNull().isGreaterThanOrEqualTo(1L);
    }

    // =========================================================================
    // TC-05 — o motivo consta do relatório
    // =========================================================================

    @Test @Order(5)
    @DisplayName("TC-05: o motivo obrigatório consta do relatório do item revertido")
    void reasonIsPresentInReport() {
        assertThat(mainRollback.itemResults().get(0).reason()).isEqualTo(REASON);
        assertThat(mainRollback.globalWarnings())
                .anyMatch(w -> w.toLowerCase().contains("motivo"));
    }

    // =========================================================================
    // TC-06 — idempotência: segundo rollback é skipped, não rebenta
    // =========================================================================

    @Test @Order(6)
    @DisplayName("TC-06: segundo rollback é idempotente/skipped (não lança INVALID_STATE_TRANSITION)")
    void secondRollbackIsIdempotent() {
        AtFaqRollbackResult second =
                rollbackService.rollbackSingleIndexedQa(mainIndexResult, org, ROLLED_BACK_BY, REASON);

        assertThat(second.blockingErrors()).isEmpty();
        assertThat(second.totals().rolledBack()).isZero();
        assertThat(second.totals().skipped()).isEqualTo(1);
        AtFaqRollbackItemResult item = second.itemResults().get(0);
        assertThat(item.alreadyRolledBack()).isTrue();
        assertThat(item.rolledBack()).isFalse();
        // Continua sem publicação e sem embedding; nada recriado.
        assertThat(embeddingRowsFor(cleanQaId)).isZero();
        assertThat(qaRepository.findById(cleanQaId).orElseThrow().isPublished()).isFalse();
    }

    // =========================================================================
    // TC-07 — motivo vazio bloqueia
    // =========================================================================

    @Test @Order(7)
    @DisplayName("TC-07: motivo vazio bloqueia o rollback (motivo obrigatório)")
    void emptyReasonIsBlocked() {
        AtFaqRollbackResult run =
                rollbackService.rollbackSingleIndexedQa(mainIndexResult, org, ROLLED_BACK_BY, "   ");

        assertThat(run.totals().rolledBack()).isZero();
        assertThat(run.blockingErrors()).anyMatch(e -> e.toLowerCase().contains("motivo"));
    }

    // =========================================================================
    // TC-08 — organização errada bloqueia
    // =========================================================================

    @Test @Order(8)
    @DisplayName("TC-08: Q&A de outra organização é bloqueado (isolamento por organização)")
    void wrongOrganizationIsBlocked() {
        KnowledgeQuestionAnswer qa = newPublishedQa("AT-FAQ-9103", KnowledgeRiskLevel.LOW, org);

        AtFaqRollbackResult run = rollbackService.rollbackSingleIndexedQa(
                wrapIndex(indexItem("AT-FAQ-9103", qa.getId())), otherOrg, ROLLED_BACK_BY, REASON);

        assertThat(run.totals().rolledBack()).isZero();
        assertThat(run.totals().blocked()).isEqualTo(1);
        assertThat(run.itemResults().get(0).blockingReasons())
                .anyMatch(r -> r.contains("não pertence à organização"));
        // Não houve despublicação: continua publicado.
        assertThat(qaRepository.findById(qa.getId()).orElseThrow().isPublished()).isTrue();
    }

    // =========================================================================
    // TC-09 — Q&A não publicado / sem embedding → skipped (já revertido), não falha
    // =========================================================================

    @Test @Order(9)
    @DisplayName("TC-09: Q&A não publicado e sem embedding é skipped (alreadyRolledBack), não falha")
    void notPublishedNoEmbeddingIsSkipped() {
        KnowledgeQuestionAnswer qa = newImportedQa("AT-FAQ-9104", KnowledgeRiskLevel.LOW, org);

        AtFaqRollbackResult run = rollbackService.rollbackSingleIndexedQa(
                wrapIndex(indexItem("AT-FAQ-9104", qa.getId())), org, ROLLED_BACK_BY, REASON);

        assertThat(run.blockingErrors()).isEmpty();
        assertThat(run.totals().rolledBack()).isZero();
        assertThat(run.totals().skipped()).isEqualTo(1);
        assertThat(run.itemResults().get(0).alreadyRolledBack()).isTrue();
    }

    // =========================================================================
    // TC-10 — mais de um item indexado elegível em modo single → bloqueado
    // =========================================================================

    @Test @Order(10)
    @DisplayName("TC-10: com 2 Q&A indexados, o modo single-Q&A bloqueia (rollback de lote é E10B)")
    void moreThanOneEligibleIsBlocked() {
        KnowledgeQuestionAnswer qaA = newPublishedQa("AT-FAQ-9105", KnowledgeRiskLevel.LOW, org);
        KnowledgeQuestionAnswer qaB = newPublishedQa("AT-FAQ-9106", KnowledgeRiskLevel.LOW, org);

        AtFaqRollbackResult run = rollbackService.rollbackSingleIndexedQa(
                wrapIndex(indexItem("AT-FAQ-9105", qaA.getId()), indexItem("AT-FAQ-9106", qaB.getId())),
                org, ROLLED_BACK_BY, REASON);

        assertThat(run.totals().rolledBack()).isZero();
        assertThat(run.blockingErrors()).anyMatch(e -> e.contains("Mais de um item"));
        // Nenhum dos dois foi despublicado.
        assertThat(qaRepository.findById(qaA.getId()).orElseThrow().isPublished()).isTrue();
        assertThat(qaRepository.findById(qaB.getId()).orElseThrow().isPublished()).isTrue();
    }

    // =========================================================================
    // TC-11 — Q&A publicado mas sem embedding → bloqueado (não falha)
    // =========================================================================

    @Test @Order(11)
    @DisplayName("TC-11: Q&A publicado mas sem embedding (embeddingRows != 1) é bloqueado, não falha")
    void publishedWithoutEmbeddingIsBlocked() {
        KnowledgeQuestionAnswer qa = newPublishedQa("AT-FAQ-9107", KnowledgeRiskLevel.LOW, org);
        assertThat(embeddingRowsFor(qa.getId())).isZero(); // publicado, mas nunca indexado

        AtFaqRollbackResult run = rollbackService.rollbackSingleIndexedQa(
                wrapIndex(indexItem("AT-FAQ-9107", qa.getId())), org, ROLLED_BACK_BY, REASON);

        assertThat(run.totals().rolledBack()).isZero();
        assertThat(run.totals().blocked()).isEqualTo(1);
        assertThat(run.itemResults().get(0).blockingReasons())
                .anyMatch(r -> r.contains("embeddingRowsBefore != 1"));
        // Guarda antes de qualquer efeito destrutivo: continua publicado.
        assertThat(qaRepository.findById(qa.getId()).orElseThrow().isPublished()).isTrue();
    }

    // =========================================================================
    // TC-12 — higiene do relatório: sem vector bruto/HTML/prompts/chunks
    // =========================================================================

    @Test @Order(12)
    @DisplayName("TC-12: relatório não contém vector bruto, HTML, prompts nem chunks")
    void reportContainsNoRawVectorHtmlPromptsOrChunks() {
        List<String> texts = new ArrayList<>();
        texts.addAll(mainRollback.globalWarnings());
        texts.addAll(mainRollback.blockingErrors());
        texts.addAll(mainRollback.nextActions());
        for (AtFaqRollbackItemResult item : mainRollback.itemResults()) {
            if (item.reason() != null) texts.add(item.reason());
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
    // TC-13 — zero chamadas externas: datasource ligado ao container local
    // =========================================================================

    @Test @Order(13)
    @DisplayName("TC-13: zero chamadas externas — datasource ligado ao container local")
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

    private void provisionActor(UUID actorId, String email, String fullName) {
        jdbc.update(
                "INSERT INTO users (id, email, full_name, password_hash, status, created_at, updated_at) "
                        + "VALUES (?::uuid, ?, ?, ?, ?, now(), now())",
                actorId.toString(), email, fullName, "n/a", "ACTIVE");
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

    /** Synthetic "indexed" item pointing at an existing Q&A, to drive negative/guard paths. */
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

    private static AtFaqRagIndexingResult wrapIndex(AtFaqRagIndexingItemResult... items) {
        int indexed = items.length;
        AtFaqRagIndexingTotals totals = new AtFaqRagIndexingTotals(
                AtFaqRagIndexingMode.SMALL_BATCH_TEST_ISOLATED,
                indexed, indexed, indexed, 0, 0, indexed, indexed);
        return new AtFaqRagIndexingResult(
                "SYNTH", FIXED_INSTANT, "test", AtFaqRagIndexingMode.SMALL_BATCH_TEST_ISOLATED,
                totals, List.of(items), List.of(), List.of(), List.of(), indexed, indexed);
    }

    private long embeddingRowsFor(UUID qaId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                Long.class, qaId.toString());
        return n != null ? n : 0L;
    }
}
