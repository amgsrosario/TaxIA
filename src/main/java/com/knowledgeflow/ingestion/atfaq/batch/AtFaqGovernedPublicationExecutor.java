package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerPublicationService;
import com.knowledgeflow.organizations.entity.Organization;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Governed AT-FAQ publication executor (Bloco E — E8B.3).
 *
 * <p>Frase-mestra: "Publicar em teste não é dar voz ao conhecimento. É provar que a promoção
 * governada até {@code publishedAt}/{@code publishedBy} respeita todos os guardas."
 *
 * <p>Given the output of E8B.2 (persisted {@code IMPORTED} drafts), this executor promotes only the
 * future-autonomy-eligible drafts to {@code VALIDATED} through the real domain API
 * ({@code markPendingReview()} + {@code validate(reviewedBy)}) and then publishes them by calling
 * the <em>real</em> {@link KnowledgeQuestionAnswerPublicationService#publish}. There is no
 * reflection, no status back-door and no bypass of the entity's own guards.
 *
 * <p>It is only ever meant to run in an isolated Testcontainers database, never the real pilot base.
 * The executor performs no indexing itself: {@code publish(...)} indexes synchronously (before
 * marking published) through whatever {@code KnowledgeQaEmbeddingIndexer} the publication service was
 * wired with. The executor does not assume which one that is — after each {@code publish(...)} it
 * <b>observes the persisted embedding state</b> and reports it truthfully:
 * <ul>
 *   <li>with the no-op {@code StubKnowledgeQaEmbeddingIndexer} (e.g. the {@code pgtest} default),
 *       publication reaches {@code publishedAt}/{@code publishedBy} while producing zero embeddings,
 *       so the RAG can never retrieve the case — reported as
 *       {@code TEST_ISOLATED_WITH_STUB_INDEXER} with {@code indexed}/{@code embeddings}/
 *       {@code ragExpected} at zero;</li>
 *   <li>with a real (synchronous) indexer, publication creates exactly one embedding per Q&amp;A in
 *       the same transaction — reported as {@code TEST_ISOLATED_WITH_SYNCHRONOUS_INDEXER} with the
 *       observed non-zero counts.</li>
 * </ul>
 * The counts are read from {@code knowledge_qa_embeddings}, never inferred from the mode; the mode is
 * itself derived from that observed evidence.
 *
 * <p>Governance guards (a draft is published only if <b>all</b> hold):
 * <ol>
 *   <li>the draft was persisted (has a {@code knowledgeQaId});</li>
 *   <li>{@code eligibleForAutoPublicationFuture == true};</li>
 *   <li>{@code requiresHumanIntervention == false};</li>
 *   <li>no persistence blocking reasons;</li>
 *   <li>the Q&amp;A exists and belongs to the supplied organization;</li>
 *   <li>curation status is {@code IMPORTED} before promotion;</li>
 *   <li>risk level is {@code LOW};</li>
 *   <li>a non-blank technical answer is present;</li>
 *   <li>an official source is present (autonomy signal {@code OFFICIAL_SOURCE_PRESENT});</li>
 *   <li>a legal reference is present on a persisted source;</li>
 *   <li>at least one source reference exists.</li>
 * </ol>
 *
 * <p>Idempotency: a second run over the same persistence result finds the Q&amp;A already published
 * and classifies it as <em>skipped (already published)</em> — it never lets {@code publish(...)}
 * throw a CONFLICT.
 */
@Service
public class AtFaqGovernedPublicationExecutor {

    private static final Logger log = LoggerFactory.getLogger(AtFaqGovernedPublicationExecutor.class);

    private final KnowledgeQuestionAnswerPublicationService publicationService;
    private final KnowledgeQuestionAnswerRepository qaRepository;
    private final KnowledgeSourceReferenceRepository sourceRepository;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public AtFaqGovernedPublicationExecutor(
            KnowledgeQuestionAnswerPublicationService publicationService,
            KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeSourceReferenceRepository sourceRepository,
            JdbcTemplate jdbc) {
        this(publicationService, qaRepository, sourceRepository, jdbc, Clock.systemUTC());
    }

    /** Test seam: inject a fixed {@link Clock}. */
    AtFaqGovernedPublicationExecutor(
            KnowledgeQuestionAnswerPublicationService publicationService,
            KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeSourceReferenceRepository sourceRepository,
            JdbcTemplate jdbc,
            Clock clock) {
        this.publicationService = publicationService;
        this.qaRepository = qaRepository;
        this.sourceRepository = sourceRepository;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Promote and publish the governed-eligible drafts of a persistence run, in an isolated DB.
     *
     * @param persistenceResult output of the E8B.2 governed draft persistence
     * @param organization      the owning organization (never the real pilot base outside tests)
     * @param publishedBy       actor recorded as publisher / reviewer
     */
    public AtFaqGovernedPublicationExecutionResult publishGoverned(
            AtFaqDraftPersistenceResult persistenceResult,
            Organization organization,
            String publishedBy) {

        Instant executedAt = clock.instant();

        if (persistenceResult == null) {
            return new AtFaqGovernedPublicationExecutionResult(
                    null, executedAt, publishedBy,
                    AtFaqGovernedPublicationExecutionMode.TEST_ISOLATED_WITH_STUB_INDEXER,
                    new AtFaqGovernedPublicationExecutionTotals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                    List.of(), List.of(), List.of("Persistência ausente."),
                    List.of("Fornecer um AtFaqDraftPersistenceResult válido."));
        }
        if (organization == null || organization.getId() == null) {
            return new AtFaqGovernedPublicationExecutionResult(
                    persistenceResult.batchId(), executedAt, publishedBy,
                    AtFaqGovernedPublicationExecutionMode.TEST_ISOLATED_WITH_STUB_INDEXER,
                    new AtFaqGovernedPublicationExecutionTotals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                    List.of(), List.of(), List.of("Organização ausente."),
                    List.of("Fornecer uma organização válida (nunca a base real do piloto)."));
        }

        UUID organizationId = organization.getId();
        UUID actingUserId = deterministicActor(publishedBy);
        String reviewedBy = (publishedBy == null || publishedBy.isBlank())
                ? "taxia-governed-publication-test" : publishedBy;

        List<AtFaqGovernedPublicationExecutionItemResult> itemResults = new ArrayList<>();
        int totalPersistedDrafts = 0;
        int eligible = 0;
        int validated = 0;
        int published = 0;
        int skipped = 0;
        int blocked = 0;
        int humanInterventionRequired = 0;
        int indexed = 0;
        int embeddings = 0;
        int ragExpected = 0;

        for (AtFaqDraftPersistenceItemResult draft : persistenceResult.itemResults()) {
            if (draft.knowledgeQaId() != null) {
                totalPersistedDrafts++;
            }
            if (draft.requiresHumanIntervention()) {
                humanInterventionRequired++;
            }

            AtFaqGovernedPublicationExecutionCommand command = plan(draft, reviewedBy);

            // Not persisted, or governance says this is not for auto-publication → skipped.
            if (!command.publish()) {
                skipped++;
                itemResults.add(skippedItem(draft, command, organizationId));
                continue;
            }
            eligible++;

            AtFaqGovernedPublicationExecutionItemResult outcome =
                    attemptPublish(draft, command, organization, organizationId, actingUserId, reviewedBy);
            itemResults.add(outcome);

            if (!outcome.blockingReasons().isEmpty()) {
                blocked++;
            } else if (outcome.validated()) {
                validated++;
                published++;
            } else {
                // Eligible but already published on a prior run (idempotent reuse).
                skipped++;
            }
        }

        // Index/embedding/RAG totals are OBSERVED from the item results, which in turn read the
        // persisted state after publish() — never assumed from the mode. Under a stub indexer these
        // stay at zero; under a synchronous real indexer they reflect the embeddings publish() made.
        for (AtFaqGovernedPublicationExecutionItemResult item : itemResults) {
            if (item.indexed()) {
                indexed++;
            }
            if (item.embeddingPresent()) {
                embeddings++;
            }
            if (item.ragExpectedToRetrieve()) {
                ragExpected++;
            }
        }

        AtFaqGovernedPublicationExecutionTotals totals = new AtFaqGovernedPublicationExecutionTotals(
                totalPersistedDrafts, eligible, validated, published, skipped, blocked,
                indexed, embeddings, ragExpected, humanInterventionRequired);

        // The mode is a descriptor derived from the same observed evidence: any real embedding means
        // publish() indexed synchronously; none means the stub (no-op) indexer was active. Both are
        // fully isolated test descriptors — never a pilot/production path.
        AtFaqGovernedPublicationExecutionMode mode = embeddings > 0
                ? AtFaqGovernedPublicationExecutionMode.TEST_ISOLATED_WITH_SYNCHRONOUS_INDEXER
                : AtFaqGovernedPublicationExecutionMode.TEST_ISOLATED_WITH_STUB_INDEXER;

        List<String> nextActions = mode == AtFaqGovernedPublicationExecutionMode.TEST_ISOLATED_WITH_SYNCHRONOUS_INDEXER
                ? List.of(
                        "Publicação indexou sincronamente (indexador real, BD isolada). Confirmar recuperação RAG.",
                        "E10: rollback/despublicação/desindexação governada.")
                : List.of(
                        "E9: indexação/RAG efectiva sob modelo de embeddings local, em ambiente próprio.",
                        "E10: rollback/despublicação/desindexação governada.");

        return new AtFaqGovernedPublicationExecutionResult(
                persistenceResult.batchId(), executedAt, publishedBy, mode,
                totals, itemResults, List.of(), List.of(), nextActions);
    }

    // ------------------------------------------------------------------------------------------

    /** Build the (inspectable) publication decision for one persisted draft. */
    private AtFaqGovernedPublicationExecutionCommand plan(
            AtFaqDraftPersistenceItemResult draft, String reviewedBy) {

        List<String> guardChecks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> blocking = new ArrayList<>();

        if (draft.knowledgeQaId() == null) {
            blocking.add("Draft não persistido: sem knowledgeQaId.");
        } else {
            guardChecks.add("Draft persistido com knowledgeQaId.");
        }
        if (draft.eligibleForAutoPublicationFuture()) {
            guardChecks.add("Autonomia futura elegível.");
        } else {
            blocking.add("Não elegível para publicação automática futura.");
        }
        if (draft.requiresHumanIntervention()) {
            blocking.add("Requer intervenção humana antes de publicar.");
        } else {
            guardChecks.add("Sem exigência de intervenção humana.");
        }
        if (!draft.blockingReasons().isEmpty()) {
            blocking.add("Draft carrega razões bloqueadoras da persistência.");
        }

        boolean publish = blocking.isEmpty();
        return new AtFaqGovernedPublicationExecutionCommand(
                draft.externalId(),
                draft.knowledgeQaId(),
                draft.normalizedQuestion(),
                publish, // validateBeforePublish — an eligible draft must be promoted first
                publish,
                false, // indexExpected
                true,  // requiresStubIndexer
                guardChecks,
                warnings,
                blocking);
    }

    /** Promote IMPORTED → VALIDATED and publish via the real service, guarding each step. */
    private AtFaqGovernedPublicationExecutionItemResult attemptPublish(
            AtFaqDraftPersistenceItemResult draft,
            AtFaqGovernedPublicationExecutionCommand command,
            Organization organization,
            UUID organizationId,
            UUID actingUserId,
            String reviewedBy) {

        UUID qaId = command.knowledgeQaId();
        Optional<KnowledgeQuestionAnswer> found = qaRepository.findById(qaId);
        if (found.isEmpty()) {
            return blockedItem(draft, qaId, null, List.of("Q&A não encontrada na BD isolada."));
        }
        KnowledgeQuestionAnswer qa = found.get();

        if (qa.getOrganization() == null || !organizationId.equals(qa.getOrganization().getId())) {
            return blockedItem(draft, qaId, qa, List.of("Q&A não pertence à organização fornecida."));
        }

        // Idempotency: already published on a prior run → skip, never trigger CONFLICT.
        if (qa.isPublished()) {
            return alreadyPublishedItem(draft, qa);
        }

        List<String> blocking = new ArrayList<>();
        if (qa.getCurationStatus() != KnowledgeCurationStatus.IMPORTED) {
            blocking.add("Estado inesperado antes da promoção: " + qa.getCurationStatus()
                    + " (esperado IMPORTED).");
        }
        if (qa.getRiskLevel() != KnowledgeRiskLevel.LOW) {
            blocking.add("Risco != LOW: " + qa.getRiskLevel() + ".");
        }
        if (isBlank(qa.getTechnicalAnswer())) {
            blocking.add("Sem resposta técnica.");
        }
        if (!draft.autonomySignals().contains("OFFICIAL_SOURCE_PRESENT")) {
            blocking.add("Sem fonte oficial (sinal OFFICIAL_SOURCE_PRESENT ausente).");
        }
        List<KnowledgeSourceReference> sources = sourceRepository.findByQuestionAnswerId(qaId);
        if (sources.isEmpty()) {
            blocking.add("Sem qualquer fonte associada.");
        }
        boolean legalReferencePresent = sources.stream()
                .anyMatch(s -> !isBlank(s.getLegalReference()));
        if (!legalReferencePresent) {
            blocking.add("Sem referência legal numa fonte persistida.");
        }

        if (!blocking.isEmpty()) {
            return blockedItem(draft, qaId, qa, blocking);
        }

        // Governed promotion through the real domain API — no reflection, no status back-door.
        try {
            qa.markPendingReview();
            qa.validate(reviewedBy);
            qaRepository.save(qa);
        } catch (RuntimeException e) {
            log.warn("Promoção governada recusada para {}: {}", draft.externalId(), e.getMessage());
            return blockedItem(draft, qaId, qa,
                    List.of("Promoção IMPORTED→VALIDATED recusada pelo domínio: " + e.getMessage()));
        }

        // Real publication. Under pgtest the stub indexer makes this produce no embedding.
        try {
            publicationService.publish(organizationId, actingUserId, reviewedBy, qaId);
        } catch (BusinessException e) {
            log.warn("Publicação recusada para {}: {}", draft.externalId(), e.getMessage());
            return blockedItem(draft, qaId, reload(qaId),
                    List.of("Publicação recusada pelo serviço: " + e.getMessage()));
        }

        KnowledgeQuestionAnswer publishedQa = reload(qaId);

        // Observe the persisted embedding state produced by publish() itself. publish() indexes
        // synchronously BEFORE marking published, so a real indexer leaves exactly one row here and
        // the stub leaves none. The knowledge_qa_id unique constraint guarantees the count is 0 or 1;
        // a count > 1 would be a real inconsistency, which we surface as a block instead of masking.
        long embeddingRows = embeddingRowsFor(qaId);
        if (embeddingRows > 1) {
            return blockedItem(draft, qaId, publishedQa, List.of(
                    "Inconsistência: %d linhas de embedding para a Q&A (esperado no máximo 1)."
                            .formatted(embeddingRows)));
        }
        boolean embeddingPresent = embeddingRows == 1;
        boolean ragExpected =
                publishedQa.isPublished() && embeddingPresent && publishedQa.isEligibleForRag();
        List<String> nextActions = embeddingPresent
                ? List.of("Publicado e indexado sincronamente em teste isolado. Confirmar recuperação RAG.")
                : List.of("Publicado em teste isolado sem indexação (stub). Indexação/RAG efectiva fica para E9.");
        return new AtFaqGovernedPublicationExecutionItemResult(
                draft.externalId(),
                qaId,
                true,  // eligibleForGovernedPublication
                true,  // validated
                publishedQa.isPublished(),
                embeddingPresent, // indexed == embeddingPresent (one row means indexed)
                publishedQa.getPublishedBy(),
                toInstant(publishedQa.getPublishedAt()),
                publishedQa.getCurationStatus(),
                publishedQa.isEligibleForRag(),
                embeddingPresent, // observed from persisted state, not assumed
                ragExpected,      // published + embedding + entity-eligible
                draft.autonomySignals(),
                List.of(),
                List.of(),
                nextActions);
    }

    private AtFaqGovernedPublicationExecutionItemResult skippedItem(
            AtFaqDraftPersistenceItemResult draft,
            AtFaqGovernedPublicationExecutionCommand command,
            UUID organizationId) {

        KnowledgeQuestionAnswer qa = draft.knowledgeQaId() == null
                ? null : reload(draft.knowledgeQaId());
        boolean published = qa != null && qa.isPublished();
        KnowledgeCurationStatus status = qa != null ? qa.getCurationStatus() : draft.curationStatus();
        boolean embeddingPresent = embeddingRowsFor(draft.knowledgeQaId()) == 1;
        boolean ragExpected =
                published && embeddingPresent && qa != null && qa.isEligibleForRag();
        List<String> nextActions = List.of(
                "Não elegível para publicação governada automática nesta etapa. "
                        + "Segue via curadoria assistida/manual quando aplicável.");
        return new AtFaqGovernedPublicationExecutionItemResult(
                draft.externalId(),
                draft.knowledgeQaId(),
                false, // eligibleForGovernedPublication
                false, // validated
                published,
                embeddingPresent,
                qa != null ? qa.getPublishedBy() : null,
                qa != null ? toInstant(qa.getPublishedAt()) : null,
                status,
                qa != null && qa.isEligibleForRag(),
                embeddingPresent,
                ragExpected,
                draft.autonomySignals(),
                draft.warnings(),
                command.blockingReasons(),
                nextActions);
    }

    private AtFaqGovernedPublicationExecutionItemResult alreadyPublishedItem(
            AtFaqDraftPersistenceItemResult draft, KnowledgeQuestionAnswer qa) {
        boolean embeddingPresent = embeddingRowsFor(qa.getId()) == 1;
        boolean ragExpected = qa.isPublished() && embeddingPresent && qa.isEligibleForRag();
        return new AtFaqGovernedPublicationExecutionItemResult(
                draft.externalId(),
                qa.getId(),
                true,  // was eligible; already satisfied
                false, // not validated in THIS run
                true,  // published (previously)
                embeddingPresent,
                qa.getPublishedBy(),
                toInstant(qa.getPublishedAt()),
                qa.getCurationStatus(),
                qa.isEligibleForRag(),
                embeddingPresent,
                ragExpected,
                draft.autonomySignals(),
                List.of("Já publicado num run anterior; reutilização idempotente."),
                List.of(),
                List.of("Nada a fazer: publicação idempotente. Estado de indexação observado na BD."));
    }

    private AtFaqGovernedPublicationExecutionItemResult blockedItem(
            AtFaqDraftPersistenceItemResult draft,
            UUID qaId,
            KnowledgeQuestionAnswer qa,
            List<String> blocking) {
        boolean published = qa != null && qa.isPublished();
        boolean embeddingPresent = embeddingRowsFor(qaId) == 1;
        boolean ragExpected =
                published && embeddingPresent && qa != null && qa.isEligibleForRag();
        return new AtFaqGovernedPublicationExecutionItemResult(
                draft.externalId(),
                qaId,
                true,  // looked eligible from persistence signals, but a guard refused it
                false,
                published,
                embeddingPresent,
                qa != null ? qa.getPublishedBy() : null,
                qa != null ? toInstant(qa.getPublishedAt()) : null,
                qa != null ? qa.getCurationStatus() : draft.curationStatus(),
                qa != null && qa.isEligibleForRag(),
                embeddingPresent,
                ragExpected,
                draft.autonomySignals(),
                draft.warnings(),
                List.copyOf(blocking),
                List.of("Corrigir a causa do bloqueio antes de publicar. Publicação recusada."));
    }

    private KnowledgeQuestionAnswer reload(UUID qaId) {
        return qaRepository.findById(qaId).orElse(null);
    }

    /**
     * Count the persisted embedding rows for a Q&amp;A. The {@code knowledge_qa_id} unique constraint
     * on {@code knowledge_qa_embeddings} means this is always 0 or 1 for a consistent database; the
     * executor treats any value {@code > 1} as an inconsistency to surface rather than mask. Reading
     * the real table (never inferring from the mode) is what lets the report tell the truth about
     * publication-without-indexing versus publication-with-synchronous-indexing.
     */
    private long embeddingRowsFor(UUID qaId) {
        if (qaId == null) {
            return 0L;
        }
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                Long.class, qaId.toString());
        return n != null ? n : 0L;
    }

    /**
     * Deterministic id of the governed-batch "acting user" for audit purposes. Derived from the
     * publisher name so the same actor id is reused across runs (idempotent audit) and can be
     * provisioned as a service-account user in the isolated test database. Package-private so the
     * IT can insert a matching {@code users} row and satisfy the audit FK.
     */
    static UUID deterministicActor(String publishedBy) {
        String seed = "taxia-governed-publication:" + (publishedBy == null ? "" : publishedBy);
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
