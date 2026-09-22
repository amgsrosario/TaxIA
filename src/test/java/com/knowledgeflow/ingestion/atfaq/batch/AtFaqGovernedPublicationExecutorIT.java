package com.knowledgeflow.ingestion.atfaq.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ingestion.atfaq.AtFaqNormalizer;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerPublicationService;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Governed AT-FAQ real publication in an isolated database (Testcontainers) — Bloco E, E8B.3.
 *
 * <p>Frase-mestra: "Publicar em teste não é dar voz ao conhecimento. É provar que a promoção
 * governada até {@code publishedAt}/{@code publishedBy} respeita todos os guardas."
 *
 * <p>Runs the full pipeline E4→…→E8B.2 in memory with a fixed clock, persists the dry-run-verified
 * drafts as {@code IMPORTED} knowledge, then drives {@link AtFaqGovernedPublicationExecutor}, which
 * promotes only the future-autonomy-eligible clean item IMPORTED → VALIDATED (through the real
 * domain API) and publishes it via the <em>real</em>
 * {@link KnowledgeQuestionAnswerPublicationService}. Under the {@code pgtest} profile the active
 * embedding indexer is the no-op {@code StubKnowledgeQaEmbeddingIndexer}, so publication reaches
 * {@code publishedAt}/{@code publishedBy} while creating <b>zero</b> embeddings — proven by asserting
 * {@code COUNT(knowledge_qa_embeddings) == 0}. The RAG can therefore never retrieve the case.
 *
 * <p>Also proves idempotency and every negative governance guard (not future-eligible, human
 * intervention required, risk != LOW, no official source, no legal reference, wrong organization).
 *
 * <p>Zero external calls, only fictitious fixture data.
 *
 * <p>Run: mvn verify -Ppgtest -Dit.test=AtFaqGovernedPublicationExecutorIT
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AtFaqGovernedPublicationExecutorIT {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-06T10:15:30Z");
    private static final String PUBLISHED_BY = "taxia-governed-publication-test";
    private static final String SOURCE_SYSTEM = "at-faq-governed-batch";

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
    private AtFaqGovernedPublicationExecutor executor;
    private AtFaqDraftPersistenceResult persistenceResult;

    // Shared across ordered tests.
    private AtFaqGovernedPublicationExecutionResult firstRun;

    @BeforeAll
    void setUp() {
        org = organizationRepository.save(new Organization("Org — Publicação E8B.3", null));

        // The real publish(...) records an audit event; Postgres enforces the audit → users FK.
        // Provision the governed-batch service-account user with the executor's deterministic id.
        provisionGovernedActor(AtFaqGovernedPublicationExecutor.deterministicActor(PUBLISHED_BY));

        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
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

        executor = new AtFaqGovernedPublicationExecutor(
                publicationService, qaRepository, sourceRepository, jdbc, clock);

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
        persistenceResult = persistenceService.persistDrafts(
                materializationResult, dryRunReport, org, "test-persister");
    }

    // =========================================================================
    // TC-01 — publica apenas o item limpo, elegível para autonomia futura
    // =========================================================================

    @Test @Order(1)
    @DisplayName("TC-01: publica exactamente o item limpo (AT-FAQ-1001) em BD isolada")
    void publishesOnlyCleanEligibleItem() {
        firstRun = executor.publishGoverned(persistenceResult, org, PUBLISHED_BY);

        assertThat(firstRun.mode())
                .isEqualTo(AtFaqGovernedPublicationExecutionMode.TEST_ISOLATED_WITH_STUB_INDEXER);
        assertThat(firstRun.executedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(firstRun.blockingErrors()).isEmpty();

        AtFaqGovernedPublicationExecutionTotals t = firstRun.totals();
        assertThat(t.totalPersistedDrafts()).isEqualTo(1);
        assertThat(t.eligibleForGovernedPublication()).isEqualTo(1);
        assertThat(t.validated()).isEqualTo(1);
        assertThat(t.published()).isEqualTo(1);
        assertThat(t.skipped()).isZero();
        assertThat(t.blocked()).isZero();
        assertThat(t.humanInterventionRequired()).isZero();
        // Hard-zero invariants.
        assertThat(t.indexed()).isZero();
        assertThat(t.embeddings()).isZero();
        assertThat(t.ragExpected()).isZero();

        assertThat(firstRun.itemResults()).hasSize(1);
        assertThat(firstRun.itemResults().get(0).externalId()).isEqualTo("AT-FAQ-1001");
    }

    // =========================================================================
    // TC-02 — estado publicado governado
    // =========================================================================

    @Test @Order(2)
    @DisplayName("TC-02: QA promovido a VALIDATED e publicado (publishedAt/publishedBy != null)")
    void publishedQaIsValidatedAndPublished() {
        KnowledgeQuestionAnswer qa = loadCleanQa();

        assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
        assertThat(qa.isPublished()).isTrue();
        assertThat(qa.getPublishedAt()).isNotNull();
        assertThat(qa.getPublishedBy()).isEqualTo(PUBLISHED_BY);
        assertThat(qa.getReviewedBy()).isNotBlank();

        AtFaqGovernedPublicationExecutionItemResult item = firstRun.itemResults().get(0);
        assertThat(item.published()).isTrue();
        assertThat(item.validated()).isTrue();
        assertThat(item.publishedBy()).isEqualTo(PUBLISHED_BY);
        assertThat(item.publishedAt()).isNotNull();
        assertThat(item.curationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
        assertThat(item.blockingReasons()).isEmpty();
    }

    // =========================================================================
    // TC-03 — elegível para RAG pelas regras da entidade, mas sem voz efectiva
    // =========================================================================

    @Test @Order(3)
    @DisplayName("TC-03: entidade considera-o RAG-elegível, mas sem embedding não é recuperável")
    void ragEligibleByEntityButNotRetrievable() {
        KnowledgeQuestionAnswer qa = loadCleanQa();
        assertThat(qa.isEligibleForRag()).isTrue();

        AtFaqGovernedPublicationExecutionItemResult item = firstRun.itemResults().get(0);
        assertThat(item.eligibleForRagByEntityRules()).isTrue();
        assertThat(item.embeddingPresent()).isFalse();
        assertThat(item.ragExpectedToRetrieve()).isFalse();
        assertThat(item.indexed()).isFalse();
    }

    // =========================================================================
    // TC-04 — limite absoluto: zero embeddings apesar da publicação
    // =========================================================================

    @Test @Order(4)
    @DisplayName("TC-04: COUNT(knowledge_qa_embeddings) == 0 apesar da publicação real")
    void noEmbeddingsDespitePublication() {
        assertThat(countEmbeddings()).isZero();
        assertThat(countAllEmbeddings()).isZero();
        assertThat(firstRun.totals().embeddings()).isZero();
        assertThat(firstRun.totals().indexed()).isZero();
    }

    // =========================================================================
    // TC-05 — idempotência: segunda execução não republica nem falha
    // =========================================================================

    @Test @Order(5)
    @DisplayName("TC-05: segunda execução classifica como já publicado (idempotente), sem CONFLICT")
    void secondRunIsIdempotent() {
        AtFaqGovernedPublicationExecutionResult secondRun =
                executor.publishGoverned(persistenceResult, org, PUBLISHED_BY);

        assertThat(secondRun.blockingErrors()).isEmpty();
        assertThat(secondRun.totals().published()).isZero();
        assertThat(secondRun.totals().validated()).isZero();
        assertThat(secondRun.totals().blocked()).isZero();
        assertThat(secondRun.totals().skipped()).isEqualTo(1);

        AtFaqGovernedPublicationExecutionItemResult item = secondRun.itemResults().get(0);
        assertThat(item.published()).isTrue();
        assertThat(item.validated()).isFalse();

        // Still exactly one published QA, still zero embeddings.
        assertThat(countPublished()).isEqualTo(1);
        assertThat(countEmbeddings()).isZero();
    }

    // =========================================================================
    // TC-06 — guarda negativa: não elegível para autonomia futura → ignorado
    // =========================================================================

    @Test @Order(6)
    @DisplayName("TC-06: draft não elegível para autonomia futura é ignorado (não publicado)")
    void notFutureEligibleIsSkipped() {
        KnowledgeQuestionAnswer qa = newImportedQa("AT-FAQ-2001", KnowledgeRiskLevel.LOW);
        addSource(qa, "Artigo 41.º do CIVA");
        AtFaqDraftPersistenceItemResult item = syntheticItem(
                "AT-FAQ-2001", qa.getId(), false, false, officialSignals());

        AtFaqGovernedPublicationExecutionResult run =
                executor.publishGoverned(wrap(item), org, PUBLISHED_BY);

        assertThat(run.totals().published()).isZero();
        assertThat(run.totals().skipped()).isEqualTo(1);
        assertThat(run.totals().blocked()).isZero();
        assertThat(run.itemResults().get(0).eligibleForGovernedPublication()).isFalse();
        assertUnpublishedImported(qa.getId());
    }

    // =========================================================================
    // TC-07 — guarda negativa: requer intervenção humana → ignorado
    // =========================================================================

    @Test @Order(7)
    @DisplayName("TC-07: draft que requer intervenção humana é ignorado (não publicado)")
    void humanInterventionRequiredIsSkipped() {
        KnowledgeQuestionAnswer qa = newImportedQa("AT-FAQ-2002", KnowledgeRiskLevel.LOW);
        addSource(qa, "Artigo 41.º do CIVA");
        AtFaqDraftPersistenceItemResult item = syntheticItem(
                "AT-FAQ-2002", qa.getId(), true, true, officialSignals());

        AtFaqGovernedPublicationExecutionResult run =
                executor.publishGoverned(wrap(item), org, PUBLISHED_BY);

        assertThat(run.totals().published()).isZero();
        assertThat(run.totals().skipped()).isEqualTo(1);
        assertThat(run.totals().humanInterventionRequired()).isEqualTo(1);
        assertUnpublishedImported(qa.getId());
    }

    // =========================================================================
    // TC-08 — guarda negativa: risco != LOW → bloqueado
    // =========================================================================

    @Test @Order(8)
    @DisplayName("TC-08: draft de risco != LOW é bloqueado (não publicado)")
    void nonLowRiskIsBlocked() {
        KnowledgeQuestionAnswer qa = newImportedQa("AT-FAQ-2003", KnowledgeRiskLevel.HIGH);
        addSource(qa, "Artigo 21.º do CIVA");
        AtFaqDraftPersistenceItemResult item = syntheticItem(
                "AT-FAQ-2003", qa.getId(), true, false, officialSignals());

        AtFaqGovernedPublicationExecutionResult run =
                executor.publishGoverned(wrap(item), org, PUBLISHED_BY);

        assertThat(run.totals().published()).isZero();
        assertThat(run.totals().blocked()).isEqualTo(1);
        assertThat(run.itemResults().get(0).blockingReasons())
                .anyMatch(r -> r.contains("Risco != LOW"));
        assertUnpublishedImported(qa.getId());
    }

    // =========================================================================
    // TC-09 — guarda negativa: sem fonte oficial → bloqueado
    // =========================================================================

    @Test @Order(9)
    @DisplayName("TC-09: draft sem sinal de fonte oficial é bloqueado (não publicado)")
    void missingOfficialSourceIsBlocked() {
        KnowledgeQuestionAnswer qa = newImportedQa("AT-FAQ-2004", KnowledgeRiskLevel.LOW);
        addSource(qa, "Artigo 41.º do CIVA");
        AtFaqDraftPersistenceItemResult item = syntheticItem(
                "AT-FAQ-2004", qa.getId(), true, false,
                List.of("LEGAL_REFERENCE_PRESENT", "TECHNICAL_ANSWER_PRESENT", "LOW_RISK"));

        AtFaqGovernedPublicationExecutionResult run =
                executor.publishGoverned(wrap(item), org, PUBLISHED_BY);

        assertThat(run.totals().published()).isZero();
        assertThat(run.totals().blocked()).isEqualTo(1);
        assertThat(run.itemResults().get(0).blockingReasons())
                .anyMatch(r -> r.contains("fonte oficial"));
        assertUnpublishedImported(qa.getId());
    }

    // =========================================================================
    // TC-10 — guarda negativa: sem referência legal → bloqueado
    // =========================================================================

    @Test @Order(10)
    @DisplayName("TC-10: draft sem referência legal numa fonte é bloqueado (não publicado)")
    void missingLegalReferenceIsBlocked() {
        KnowledgeQuestionAnswer qa = newImportedQa("AT-FAQ-2005", KnowledgeRiskLevel.LOW);
        addSource(qa, null); // source with no legal reference
        AtFaqDraftPersistenceItemResult item = syntheticItem(
                "AT-FAQ-2005", qa.getId(), true, false, officialSignals());

        AtFaqGovernedPublicationExecutionResult run =
                executor.publishGoverned(wrap(item), org, PUBLISHED_BY);

        assertThat(run.totals().published()).isZero();
        assertThat(run.totals().blocked()).isEqualTo(1);
        assertThat(run.itemResults().get(0).blockingReasons())
                .anyMatch(r -> r.contains("referência legal"));
        assertUnpublishedImported(qa.getId());
    }

    // =========================================================================
    // TC-11 — guarda negativa: organização errada → bloqueado
    // =========================================================================

    @Test @Order(11)
    @DisplayName("TC-11: QA de outra organização é bloqueado (isolamento por organização)")
    void wrongOrganizationIsBlocked() {
        Organization other = organizationRepository.save(new Organization("Org — Outra E8B.3", null));
        KnowledgeQuestionAnswer qa = newImportedQa("AT-FAQ-2006", KnowledgeRiskLevel.LOW, other);
        addSource(qa, "Artigo 41.º do CIVA");
        AtFaqDraftPersistenceItemResult item = syntheticItem(
                "AT-FAQ-2006", qa.getId(), true, false, officialSignals());

        // Ask to publish it under `org`, but the QA belongs to `other`.
        AtFaqGovernedPublicationExecutionResult run =
                executor.publishGoverned(wrap(item), org, PUBLISHED_BY);

        assertThat(run.totals().published()).isZero();
        assertThat(run.totals().blocked()).isEqualTo(1);
        assertThat(run.itemResults().get(0).blockingReasons())
                .anyMatch(r -> r.contains("não pertence à organização"));
        assertUnpublishedImported(qa.getId());
    }

    // =========================================================================
    // TC-12 — higiene do relatório: sem HTML bruto/prompts/chunks/embeddings
    // =========================================================================

    @Test @Order(12)
    @DisplayName("TC-12: relatório não contém HTML bruto, prompts nem chunks")
    void outputContainsNoRawHtmlPromptsOrChunks() {
        List<String> texts = new ArrayList<>();
        texts.addAll(firstRun.globalWarnings());
        texts.addAll(firstRun.blockingErrors());
        texts.addAll(firstRun.nextActions());
        for (AtFaqGovernedPublicationExecutionItemResult item : firstRun.itemResults()) {
            texts.addAll(item.autonomySignals());
            texts.addAll(item.warnings());
            texts.addAll(item.blockingReasons());
            texts.addAll(item.nextActions());
        }
        // Item-level payloads must carry no raw HTML, prompts or chunks. (Descriptive batch-level
        // next-actions may name "embeddings" when pointing at the future E9 step; the hard
        // zero-embeddings guarantee is proven structurally by TC-04, not by string matching.)
        for (String s : texts) {
            if (s != null) {
                assertThat(s).doesNotContain("<").doesNotContain("```");
                assertThat(s.toLowerCase()).doesNotContain("prompt").doesNotContain("chunk");
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void provisionGovernedActor(java.util.UUID actorId) {
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

    private KnowledgeQuestionAnswer newImportedQa(String externalKey, KnowledgeRiskLevel risk) {
        return newImportedQa(externalKey, risk, org);
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

    private void addSource(KnowledgeQuestionAnswer qa, String legalReference) {
        KnowledgeSourceReference source = new KnowledgeSourceReference(
                qa, KnowledgeSourceType.LEGISLATION, "Fonte oficial " + qa.getExternalKey());
        source.update(KnowledgeSourceType.LEGISLATION, "Fonte oficial", legalReference,
                null, null, null, null, null, null);
        sourceRepository.save(source);
    }

    private static List<String> officialSignals() {
        return List.of("AUTO_PUBLICATION_FUTURE_ELIGIBLE", "OFFICIAL_SOURCE_PRESENT",
                "LEGAL_REFERENCE_PRESENT", "TECHNICAL_ANSWER_PRESENT", "LOW_RISK",
                "NO_CONFLICTS", "NO_BLOCKING_DUPLICATE");
    }

    private static AtFaqDraftPersistenceItemResult syntheticItem(
            String externalId, java.util.UUID qaId, boolean autoFuture, boolean humanNeeded,
            List<String> signals) {
        return new AtFaqDraftPersistenceItemResult(
                externalId, true, true, true, false, false, qaId,
                "Pergunta normalizada " + externalId,
                KnowledgeCurationStatus.IMPORTED, autoFuture, humanNeeded,
                signals, List.of(), List.of(), List.of());
    }

    private static AtFaqDraftPersistenceResult wrap(AtFaqDraftPersistenceItemResult item) {
        return new AtFaqDraftPersistenceResult(
                "SYNTH", FIXED_INSTANT, "test", AtFaqDraftPersistenceMode.DRY_RUN_VERIFIED,
                new AtFaqDraftPersistenceTotals(1, 1, 1, 1, 0, 0, 1, 0, 0, 0, 0),
                List.of(item), List.of(), List.of(), List.of());
    }

    private void assertUnpublishedImported(java.util.UUID qaId) {
        KnowledgeQuestionAnswer qa = qaRepository.findById(qaId).orElseThrow();
        assertThat(qa.isPublished()).isFalse();
        assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.IMPORTED);
    }

    private long countPublished() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_question_answers "
                        + "WHERE organization_id = ?::uuid AND published_at IS NOT NULL",
                Long.class, org.getId().toString());
        return n != null ? n : 0L;
    }

    private long countEmbeddings() {
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

    private long countAllEmbeddings() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_qa_embeddings", Long.class);
        return n != null ? n : 0L;
    }
}
