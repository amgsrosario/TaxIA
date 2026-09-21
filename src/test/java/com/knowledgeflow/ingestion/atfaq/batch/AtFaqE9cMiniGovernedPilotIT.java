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
 * E9C-mini — governed end-to-end pilot of exactly N=3 in an isolated database (Testcontainers),
 * Bloco E.
 *
 * <p>Frase-mestra: "Provar o mecanismo completo — seleccionar, decidir, publicar, indexar, recuperar
 * e reverter — sem tocar na base piloto real, sem modelo de embeddings real e sem provider externo."
 *
 * <p>This IT is the <b>governed pilot proof</b> requested in PROMPT 79. It does not add any new
 * production code: it wires the <em>existing</em> governed services into one controlled N=3 cycle
 * against an isolated {@code pgvector} container and a deterministic in-test embedding, and asserts
 * every governance invariant end to end:
 * <ol>
 *   <li><b>Selecção governada + matriz de decisão</b> — three isolated, LOW-risk, official-source
 *       drafts are fed to {@link AtFaqGovernedPublicationExecutor}, which decides each is
 *       auto-governed eligible (eligible=3, selected=3, blocked=0, human=0);</li>
 *   <li><b>Publicação governada</b> — the real {@link KnowledgeQuestionAnswerPublicationService}
 *       promotes IMPORTED→VALIDATED and publishes; under {@code pgtest} the stub indexer means
 *       publication alone creates zero embeddings;</li>
 *   <li><b>Indexação</b> — {@link AtFaqGovernedRagIndexingService} gives controlled voice through the
 *       <b>real</b> {@link KnowledgeQaEmbeddingIndexerImpl}: exactly one embedding per Q&amp;A, three
 *       in total;</li>
 *   <li><b>RAG</b> — a real {@link RagSearchService} retrieves all three before rollback;</li>
 *   <li><b>Idempotência</b> — re-publishing and re-indexing create neither a duplicate nor a fourth
 *       item;</li>
 *   <li><b>Rollback governado dos 3</b> — {@link AtFaqGovernedRollbackService#rollbackSmallIndexedBatch}
 *       with a taxonomy motive ({@code PUBLICATION_ERROR}) unpublishes and de-indexes all three:
 *       {@code publishedAt/By} cleared, curation kept {@code VALIDATED}, embeddings removed, RAG no
 *       longer retrieves them, {@code KNOWLEDGE_QA_UNPUBLISHED} audited with the persisted reason;</li>
 *   <li><b>Idempotência do rollback</b> — a second rollback is fully skipped, nothing re-created;</li>
 *   <li><b>Isolamento</b> — datasource is the local container; a second organization is never
 *       touched; the pilot never runs against {@code knowledgeflow_pilot}.</li>
 * </ol>
 *
 * <p>Run: mvn verify -Ppgtest -Dit.test=AtFaqE9cMiniGovernedPilotIT
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AtFaqE9cMiniGovernedPilotIT {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-21T09:30:00Z");
    private static final String PUBLISHED_BY = "taxia-governed-publication-test";
    private static final String INDEXED_BY = "taxia-e9cmini-indexing-test";
    private static final String ROLLED_BACK_BY = "taxia-e9cmini-rollback-test";
    private static final String SOURCE_SYSTEM = "at-faq-e9cmini-isolated";

    // Rollback motive: this is a deliberate test publication being withdrawn — PUBLICATION_ERROR is
    // the taxonomy value for "revert a publication", and it needs no complementary detail.
    private static final AtFaqRollbackMotive MOTIVE =
            AtFaqRollbackMotive.of(AtFaqRollbackReason.PUBLICATION_ERROR);

    private static final String RAG_QUERY = "Qual o enquadramento em IVA desta operação?";
    private static final int DIM = 768;
    // Generous top-k: the deterministic embedding is identical for every Q&A (similarity ≈ 1.0), so a
    // small k could truncate a live target on ties. A large k makes the before/after RAG assertions
    // deterministic without any artificial false negative.
    private static final int TOP_K = 50;

    // Exactly three isolated fixtures — clearly synthetic ids, never a real known AT-FAQ id.
    private static final List<String> CANDIDATES =
            List.of("E9CMINI-FIX-001", "E9CMINI-FIX-002", "E9CMINI-FIX-003");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired KnowledgeSourceReferenceRepository sourceRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired KnowledgeQuestionAnswerPublicationService publicationService; // stub indexer (pgtest)
    @Autowired AuditService auditService;
    @Autowired JdbcTemplate jdbc;

    private Organization org;
    private Organization otherOrg;

    private AtFaqGovernedPublicationExecutor executor;
    private AtFaqGovernedRagIndexingService ragIndexingService;
    private AtFaqGovernedRollbackService rollbackService;
    private RagSearchService ragSearch;

    private final List<UUID> candidateQaIds = new ArrayList<>();
    private AtFaqDraftPersistenceResult persistenceResult;
    private AtFaqGovernedPublicationExecutionResult publicationResult;
    private AtFaqRagIndexingResult indexingResult;

    @BeforeAll
    void setUp() {
        org = organizationRepository.save(new Organization("Org — E9C-mini isolada", null));
        otherOrg = organizationRepository.save(new Organization("Org — Outra E9C-mini", null));

        // The real publish(...) / unpublish(...) record audit events; Postgres enforces audit → users.
        provisionActor(AtFaqGovernedPublicationExecutor.deterministicActor(PUBLISHED_BY),
                "e9cmini-publication@taxia.test", "TaxIA E9C-mini Publication (service account)");
        provisionActor(AtFaqGovernedRollbackService.deterministicActor(ROLLED_BACK_BY),
                "e9cmini-rollback@taxia.test", "TaxIA E9C-mini Rollback (service account)");

        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        EmbeddingService embedding = fixedEmbedding();

        // Governed publication executor uses the autowired publication service (stub indexer under
        // pgtest) — publishing alone therefore produces zero embeddings.
        executor = new AtFaqGovernedPublicationExecutor(
                publicationService, qaRepository, sourceRepository, clock);

        // Governed indexing uses the REAL indexer fed by the deterministic embedding — genuine
        // pgvector rows, no external call.
        KnowledgeQaEmbeddingIndexerImpl realIndexer = new KnowledgeQaEmbeddingIndexerImpl(embedding, jdbc);
        ragIndexingService = new AtFaqGovernedRagIndexingService(
                realIndexer, qaRepository, sourceRepository, jdbc, clock);

        // Real RAG search over the same deterministic embedding, plus a probe for the rollback service.
        ragSearch = new RagSearchService(
                embedding, jdbc,
                new EmbeddingProperties(null, TOP_K, null, null, null, null, null),
                new KnowledgeFlowMetrics(new SimpleMeterRegistry()));
        AtFaqRollbackRagProbe probe = (organizationId, qaId) ->
                ragSearch.findSimilar(organizationId, RAG_QUERY).stream()
                        .anyMatch(r -> qaId.equals(r.sourceQaId()));

        // Rollback service wired with a publication service using the SAME real indexer, so each
        // unpublish(...) truly deletes the embedding (the pgtest-autowired one would no-op).
        KnowledgeQuestionAnswerPublicationService rollbackPublicationService =
                new KnowledgeQuestionAnswerPublicationService(
                        qaRepository, sourceRepository, realIndexer, auditService,
                        new KnowledgeFlowMetrics(new SimpleMeterRegistry()));
        rollbackService = new AtFaqGovernedRollbackService(
                rollbackPublicationService, qaRepository, jdbc, probe, clock);

        // Selecção governada: exactly three isolated, LOW-risk, official-source IMPORTED drafts.
        List<AtFaqDraftPersistenceItemResult> drafts = new ArrayList<>();
        for (String ext : CANDIDATES) {
            KnowledgeQuestionAnswer qa = newImportedQaWithOfficialSource(ext);
            candidateQaIds.add(qa.getId());
            drafts.add(eligibleDraft(ext, qa.getId()));
        }
        persistenceResult = wrapPersistence(drafts);
    }

    // =========================================================================
    // PASSO 2/3 — isolamento + selecção: 3 candidatos elegíveis, ainda por publicar
    // =========================================================================

    @Test @Order(1)
    @DisplayName("P1: 3 candidatos isolados LOW/fonte oficial, IMPORTED, não publicados, 0 embeddings")
    void isolatedCandidatesSelected() {
        // Isolamento do datasource: container local, nunca base piloto real.
        assertThat(postgres.getJdbcUrl()).startsWith("jdbc:postgresql://");
        assertThat(postgres.getHost()).isIn("localhost", "127.0.0.1");
        assertThat(postgres.getDatabaseName()).isNotEqualTo("knowledgeflow_pilot");

        assertThat(candidateQaIds).hasSize(3);
        for (UUID qaId : candidateQaIds) {
            KnowledgeQuestionAnswer qa = qaRepository.findById(qaId).orElseThrow();
            assertThat(qa.getOrganization().getId()).isEqualTo(org.getId());
            assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.IMPORTED);
            assertThat(qa.getRiskLevel()).isEqualTo(KnowledgeRiskLevel.LOW);
            assertThat(qa.isPublished()).isFalse();
            assertThat(embeddingRowsFor(qaId)).isZero();
            assertThat(sourceRepository.countByQuestionAnswerId(qaId)).isGreaterThanOrEqualTo(1);
        }
        // Nenhum id fiscal real conhecido foi reutilizado.
        assertThat(CANDIDATES).noneMatch(id -> id.startsWith("AT-FAQ-") || id.startsWith("ANP-"));
    }

    // =========================================================================
    // PASSO 4/5 — preview + publicação governada: eligible=3, selected=3, blocked=0, manual=0
    // =========================================================================

    @Test @Order(2)
    @DisplayName("P2: publicação governada — 3 elegíveis, 3 publicados, 0 bloqueados, 0 manual, 0 embeddings")
    void governedPublicationOfThree() {
        publicationResult = executor.publishGoverned(persistenceResult, org, PUBLISHED_BY);

        assertThat(publicationResult.mode())
                .isEqualTo(AtFaqGovernedPublicationExecutionMode.TEST_ISOLATED_WITH_STUB_INDEXER);
        assertThat(publicationResult.executedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(publicationResult.blockingErrors()).isEmpty();

        AtFaqGovernedPublicationExecutionTotals t = publicationResult.totals();
        assertThat(t.totalPersistedDrafts()).isEqualTo(3);
        assertThat(t.eligibleForGovernedPublication()).isEqualTo(3); // eligible = 3
        assertThat(t.validated()).isEqualTo(3);
        assertThat(t.published()).isEqualTo(3);                      // selected = 3
        assertThat(t.blocked()).isZero();                           // blocked = 0
        assertThat(t.skipped()).isZero();                           // assisted/deferred = 0
        assertThat(t.humanInterventionRequired()).isZero();         // manual = 0
        // Publicação sozinha não indexa: invariantes zero-embedding sob o stub indexer.
        assertThat(t.indexed()).isZero();
        assertThat(t.embeddings()).isZero();
        assertThat(t.ragExpected()).isZero();

        assertThat(publicationResult.itemResults()).hasSize(3);
        assertThat(publicationResult.itemResults()).allSatisfy(item -> {
            assertThat(item.eligibleForGovernedPublication()).isTrue(); // AUTO_GOVERNED
            assertThat(item.validated()).isTrue();
            assertThat(item.published()).isTrue();
            assertThat(item.publishedBy()).isEqualTo(PUBLISHED_BY);
            assertThat(item.publishedAt()).isNotNull();
            assertThat(item.curationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
            assertThat(item.embeddingPresent()).isFalse();
            assertThat(item.blockingReasons()).isEmpty();
        });

        // Estado persistido: cada Q&A VALIDATED + publishedAt/By, mas ainda sem voz no RAG.
        for (UUID qaId : candidateQaIds) {
            KnowledgeQuestionAnswer qa = qaRepository.findById(qaId).orElseThrow();
            assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
            assertThat(qa.isPublished()).isTrue();
            assertThat(qa.getPublishedAt()).isNotNull();
            assertThat(qa.getPublishedBy()).isEqualTo(PUBLISHED_BY);
            assertThat(embeddingRowsFor(qaId)).isZero();
            // Auditoria de publicação presente.
            assertThat(auditCount(qaId, "KNOWLEDGE_QA_PUBLISHED")).isGreaterThanOrEqualTo(1L);
        }
    }

    // =========================================================================
    // PASSO 6 — indexação: exactamente 1 embedding por item, 3 no total
    // =========================================================================

    @Test @Order(3)
    @DisplayName("P3: indexação governada dos 3 — exactamente 1 embedding por Q&A (3 no total)")
    void governedIndexingOfThree() {
        indexingResult = ragIndexingService.indexSmallPublishedBatch(publicationResult, org, INDEXED_BY, 3);

        assertThat(indexingResult.mode()).isEqualTo(AtFaqRagIndexingMode.SMALL_BATCH_TEST_ISOLATED);
        assertThat(indexingResult.blockingErrors()).isEmpty();
        assertThat(indexingResult.requestedMaxItems()).isEqualTo(3);
        assertThat(indexingResult.effectiveMaxItems()).isEqualTo(3);

        AtFaqRagIndexingTotals t = indexingResult.totals();
        assertThat(t.totalPublishedItems()).isEqualTo(3);
        assertThat(t.eligibleForIndexing()).isEqualTo(3);
        assertThat(t.indexed()).isEqualTo(3);
        assertThat(t.skipped()).isZero();
        assertThat(t.blocked()).isZero();
        assertThat(t.embeddingRows()).isEqualTo(3);
        assertThat(t.ragExpected()).isEqualTo(3);

        assertThat(indexingResult.itemResults()).hasSize(3);
        assertThat(indexingResult.itemResults())
                .allMatch(AtFaqRagIndexingItemResult::indexed)
                .allMatch(AtFaqRagIndexingItemResult::embeddingPresent)
                .allMatch(i -> i.embeddingRows() == 1);

        for (UUID qaId : candidateQaIds) {
            assertThat(embeddingRowsFor(qaId)).isEqualTo(1);
        }
        assertThat(countEmbeddingsForOrg(org)).isEqualTo(3);
    }

    // =========================================================================
    // PASSO 7 — RAG recupera os 3 antes do rollback
    // =========================================================================

    @Test @Order(4)
    @DisplayName("P4: RAG recupera os 3 Q&A indexados antes do rollback (pertença ao conjunto)")
    void ragRetrievesAllThreeBeforeRollback() {
        List<UUID> retrieved = ragSearch.findSimilar(org.getId(), RAG_QUERY).stream()
                .map(RagSearchService.RetrievedCase::sourceQaId)
                .toList();
        assertThat(retrieved).contains(
                candidateQaIds.get(0), candidateQaIds.get(1), candidateQaIds.get(2));
        for (UUID qaId : candidateQaIds) {
            assertThat(ragRetrieves(qaId)).isTrue();
        }
    }

    // =========================================================================
    // PASSO 8 — idempotência de publicação e indexação (nada duplicado, nenhum 4.º item)
    // =========================================================================

    @Test @Order(5)
    @DisplayName("P5: reexecutar publicação e indexação é idempotente — 3 embeddings, sem 4.º item")
    void publicationAndIndexingAreIdempotent() {
        // Re-publicação governada: já publicados → skipped, nada republicado, sem CONFLICT.
        AtFaqGovernedPublicationExecutionResult rePub =
                executor.publishGoverned(persistenceResult, org, PUBLISHED_BY);
        assertThat(rePub.blockingErrors()).isEmpty();
        assertThat(rePub.totals().published()).isZero();
        assertThat(rePub.totals().validated()).isZero();
        assertThat(rePub.totals().blocked()).isZero();
        assertThat(rePub.totals().skipped()).isEqualTo(3);

        // Re-indexação: o indexador faz upsert por knowledge_qa_id → continua 1 linha por Q&A.
        AtFaqRagIndexingResult reIndex =
                ragIndexingService.indexSmallPublishedBatch(publicationResult, org, INDEXED_BY, 3);
        assertThat(reIndex.blockingErrors()).isEmpty();
        assertThat(reIndex.totals().indexed()).isEqualTo(3);
        assertThat(reIndex.totals().embeddingRows()).isEqualTo(3);

        for (UUID qaId : candidateQaIds) {
            assertThat(embeddingRowsFor(qaId)).isEqualTo(1);
        }
        assertThat(countEmbeddingsForOrg(org)).isEqualTo(3);
        // Nenhum quarto item: continuam exactamente 3 Q&A publicados na organização.
        assertThat(countPublishedForOrg(org)).isEqualTo(3);
    }

    // =========================================================================
    // PASSO 9/10 — rollback governado dos 3 + prova pós-rollback + motivo persistido
    // =========================================================================

    @Test @Order(6)
    @DisplayName("P6: rollback governado dos 3 — despublicados, VALIDATED, 0 embeddings, RAG vazio, motivo persistido")
    void governedRollbackOfThree() {
        AtFaqRollbackResult run =
                rollbackService.rollbackSmallIndexedBatch(indexingResult, org, ROLLED_BACK_BY, MOTIVE, 3);

        assertThat(run.mode()).isEqualTo(AtFaqRollbackMode.SMALL_BATCH_TEST_ISOLATED);
        assertThat(run.rolledBackAt()).isEqualTo(FIXED_INSTANT);
        assertThat(run.rolledBackBy()).isEqualTo(ROLLED_BACK_BY);
        assertThat(run.blockingErrors()).isEmpty();

        AtFaqRollbackTotals t = run.totals();
        assertThat(t.eligibleForRollback()).isEqualTo(3);
        assertThat(t.rolledBack()).isEqualTo(3);                 // rolledBack = 3
        assertThat(t.unpublished()).isEqualTo(3);
        assertThat(t.deindexed()).isEqualTo(3);
        assertThat(t.embeddingRowsBefore()).isEqualTo(3);
        assertThat(t.embeddingRowsAfter()).isZero();
        assertThat(t.ragRecoveredBefore()).isEqualTo(3);
        assertThat(t.ragRecoveredAfter()).isZero();
        assertThat(t.deferredDueToLimit()).isZero();            // deferredDueToLimit = 0
        assertThat(t.skipped()).isZero();
        assertThat(t.skippedAlreadyRolledBack()).isZero();
        assertThat(t.blocked()).isZero();

        assertThat(run.itemResults()).hasSize(3);
        assertThat(run.itemResults()).allSatisfy(item -> {
            assertThat(item.rolledBack()).isTrue();
            assertThat(item.unpublished()).isTrue();
            assertThat(item.deindexed()).isTrue();
            assertThat(item.embeddingRemoved()).isTrue();
            assertThat(item.ragRecoveredBefore()).isTrue();
            assertThat(item.ragRecoveredAfter()).isFalse();
            assertThat(item.reason()).isEqualTo(MOTIVE.auditDetail()); // reasonCode=PUBLICATION_ERROR
        });

        // Prova pós-rollback, item a item, contra a BD real.
        for (UUID qaId : candidateQaIds) {
            KnowledgeQuestionAnswer qa = qaRepository.findById(qaId).orElseThrow();
            assertThat(qa.isPublished()).isFalse();
            assertThat(qa.getPublishedAt()).isNull();
            assertThat(qa.getPublishedBy()).isNull();
            // Rollback neutraliza a recuperação; não rebaixa a curadoria.
            assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
            assertThat(embeddingRowsFor(qaId)).isZero();
            assertThat(ragRetrieves(qaId)).isFalse();
            // Auditoria KNOWLEDGE_QA_UNPUBLISHED e motivo persistido.
            assertThat(auditCount(qaId, "KNOWLEDGE_QA_UNPUBLISHED")).isGreaterThanOrEqualTo(1L);
            assertThat(unpublishMetadatas(qaId)).anyMatch(m -> m != null && m.contains("reasonCode=PUBLICATION_ERROR"));
        }
        assertThat(countEmbeddingsForOrg(org)).isZero();
    }

    // =========================================================================
    // PASSO 11 — idempotência do rollback: segundo rollback totalmente skipped
    // =========================================================================

    @Test @Order(7)
    @DisplayName("P7: segundo rollback é idempotente — rolledBack=0, skippedAlreadyRolledBack=3, nada recriado")
    void rollbackIsIdempotent() {
        AtFaqRollbackResult run =
                rollbackService.rollbackSmallIndexedBatch(indexingResult, org, ROLLED_BACK_BY, MOTIVE, 3);

        assertThat(run.blockingErrors()).isEmpty();
        AtFaqRollbackTotals t = run.totals();
        assertThat(t.rolledBack()).isZero();                       // rolledBack = 0
        assertThat(t.skippedAlreadyRolledBack()).isEqualTo(3);     // skippedAlreadyRolledBack = 3
        assertThat(t.skipped()).isEqualTo(3);
        assertThat(t.deferredDueToLimit()).isZero();
        assertThat(t.blocked()).isZero();

        assertThat(run.itemResults()).hasSize(3);
        assertThat(run.itemResults()).allSatisfy(item -> {
            assertThat(item.alreadyRolledBack()).isTrue();
            assertThat(item.skippedAlreadyRolledBack()).isTrue();
            assertThat(item.rolledBack()).isFalse();
        });

        // Nada recriado.
        for (UUID qaId : candidateQaIds) {
            assertThat(embeddingRowsFor(qaId)).isZero();
            assertThat(qaRepository.findById(qaId).orElseThrow().isPublished()).isFalse();
            assertThat(ragRetrieves(qaId)).isFalse();
        }
        assertThat(countEmbeddingsForOrg(org)).isZero();
    }

    // =========================================================================
    // PASSO 13 — isolamento: a segunda organização nunca foi tocada
    // =========================================================================

    @Test @Order(8)
    @DisplayName("P8: isolamento — a segunda organização não tem nada publicado nem indexado")
    void otherOrganizationUntouched() {
        assertThat(countPublishedForOrg(otherOrg)).isZero();
        assertThat(countEmbeddingsForOrg(otherOrg)).isZero();
        // Todo o efeito do piloto ficou confinado ao container local isolado.
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

    /** Isolated, LOW-risk, official-source IMPORTED draft — a governed-eligible candidate fixture. */
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

    private static AtFaqDraftPersistenceItemResult eligibleDraft(String externalId, UUID qaId) {
        List<String> signals = List.of(
                "AUTO_PUBLICATION_FUTURE_ELIGIBLE", "OFFICIAL_SOURCE_PRESENT",
                "LEGAL_REFERENCE_PRESENT", "TECHNICAL_ANSWER_PRESENT", "LOW_RISK",
                "NO_CONFLICTS", "NO_BLOCKING_DUPLICATE");
        return new AtFaqDraftPersistenceItemResult(
                externalId, true, true, true, false, false, qaId,
                "Pergunta normalizada " + externalId,
                KnowledgeCurationStatus.IMPORTED,
                true,  // eligibleForAutoPublicationFuture
                false, // requiresHumanIntervention
                signals, List.of(), List.of(), List.of());
    }

    private static AtFaqDraftPersistenceResult wrapPersistence(List<AtFaqDraftPersistenceItemResult> items) {
        int n = items.size();
        return new AtFaqDraftPersistenceResult(
                "E9CMINI", FIXED_INSTANT, "test", AtFaqDraftPersistenceMode.DRY_RUN_VERIFIED,
                new AtFaqDraftPersistenceTotals(n, n, n, n, 0, 0, n, 0, 0, 0, 0),
                List.copyOf(items), List.of(), List.of(), List.of());
    }

    private boolean ragRetrieves(UUID qaId) {
        return ragSearch.findSimilar(org.getId(), RAG_QUERY).stream()
                .anyMatch(r -> qaId.equals(r.sourceQaId()));
    }

    private long auditCount(UUID qaId, String action) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE entity_id = ?::uuid AND action = ?",
                Long.class, qaId.toString(), action);
        return n != null ? n : 0L;
    }

    private List<String> unpublishMetadatas(UUID qaId) {
        return jdbc.queryForList(
                "SELECT metadata FROM audit_events WHERE entity_id = ?::uuid AND action = ?",
                String.class, qaId.toString(), "KNOWLEDGE_QA_UNPUBLISHED");
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

    private long countPublishedForOrg(Organization organization) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_question_answers "
                        + "WHERE organization_id = ?::uuid AND published_at IS NOT NULL",
                Long.class, organization.getId().toString());
        return n != null ? n : 0L;
    }
}
