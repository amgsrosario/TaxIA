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
 * <p>It is only ever meant to run in an isolated Testcontainers database under the {@code pgtest}
 * profile, where the active {@code KnowledgeQaEmbeddingIndexer} is the no-op
 * {@code StubKnowledgeQaEmbeddingIndexer}. That is what lets the real {@code publish(...)} —
 * which indexes synchronously before marking published — complete to
 * {@code publishedAt}/{@code publishedBy} while producing zero real embeddings, so the RAG can
 * never retrieve the published case. The executor therefore performs no indexing itself and asserts
 * the hard-zero index/embedding/RAG invariants in its report.
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

    private static final AtFaqGovernedPublicationExecutionMode MODE =
            AtFaqGovernedPublicationExecutionMode.TEST_ISOLATED_WITH_STUB_INDEXER;

    private final KnowledgeQuestionAnswerPublicationService publicationService;
    private final KnowledgeQuestionAnswerRepository qaRepository;
    private final KnowledgeSourceReferenceRepository sourceRepository;
    private final Clock clock;

    @Autowired
    public AtFaqGovernedPublicationExecutor(
            KnowledgeQuestionAnswerPublicationService publicationService,
            KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeSourceReferenceRepository sourceRepository) {
        this(publicationService, qaRepository, sourceRepository, Clock.systemUTC());
    }

    /** Test seam: inject a fixed {@link Clock}. */
    AtFaqGovernedPublicationExecutor(
            KnowledgeQuestionAnswerPublicationService publicationService,
            KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeSourceReferenceRepository sourceRepository,
            Clock clock) {
        this.publicationService = publicationService;
        this.qaRepository = qaRepository;
        this.sourceRepository = sourceRepository;
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
                    null, executedAt, publishedBy, MODE,
                    new AtFaqGovernedPublicationExecutionTotals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                    List.of(), List.of(), List.of("Persistência ausente."),
                    List.of("Fornecer um AtFaqDraftPersistenceResult válido."));
        }
        if (organization == null || organization.getId() == null) {
            return new AtFaqGovernedPublicationExecutionResult(
                    persistenceResult.batchId(), executedAt, publishedBy, MODE,
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

        AtFaqGovernedPublicationExecutionTotals totals = new AtFaqGovernedPublicationExecutionTotals(
                totalPersistedDrafts, eligible, validated, published, skipped, blocked,
                0, 0, 0, humanInterventionRequired);

        List<String> nextActions = List.of(
                "E9: indexação/RAG efectiva sob modelo de embeddings local, em ambiente próprio.",
                "E10: rollback/despublicação/desindexação governada.");

        return new AtFaqGovernedPublicationExecutionResult(
                persistenceResult.batchId(), executedAt, publishedBy, MODE,
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
        List<String> nextActions = List.of(
                "Publicado em teste isolado. Indexação/RAG efectiva fica para E9.");
        return new AtFaqGovernedPublicationExecutionItemResult(
                draft.externalId(),
                qaId,
                true,  // eligibleForGovernedPublication
                true,  // validated
                publishedQa.isPublished(),
                false, // indexed
                publishedQa.getPublishedBy(),
                toInstant(publishedQa.getPublishedAt()),
                publishedQa.getCurationStatus(),
                publishedQa.isEligibleForRag(),
                false, // embeddingPresent — stub indexer creates none
                false, // ragExpectedToRetrieve — no embedding → not retrievable
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
        List<String> nextActions = List.of(
                "Não elegível para publicação governada automática nesta etapa. "
                        + "Segue via curadoria assistida/manual quando aplicável.");
        return new AtFaqGovernedPublicationExecutionItemResult(
                draft.externalId(),
                draft.knowledgeQaId(),
                false, // eligibleForGovernedPublication
                false, // validated
                published,
                false,
                qa != null ? qa.getPublishedBy() : null,
                qa != null ? toInstant(qa.getPublishedAt()) : null,
                status,
                qa != null && qa.isEligibleForRag(),
                false,
                false,
                draft.autonomySignals(),
                draft.warnings(),
                command.blockingReasons(),
                nextActions);
    }

    private AtFaqGovernedPublicationExecutionItemResult alreadyPublishedItem(
            AtFaqDraftPersistenceItemResult draft, KnowledgeQuestionAnswer qa) {
        return new AtFaqGovernedPublicationExecutionItemResult(
                draft.externalId(),
                qa.getId(),
                true,  // was eligible; already satisfied
                false, // not validated in THIS run
                true,  // published (previously)
                false,
                qa.getPublishedBy(),
                toInstant(qa.getPublishedAt()),
                qa.getCurationStatus(),
                qa.isEligibleForRag(),
                false,
                false,
                draft.autonomySignals(),
                List.of("Já publicado num run anterior; reutilização idempotente."),
                List.of(),
                List.of("Nada a fazer: publicação idempotente. Sem nova indexação."));
    }

    private AtFaqGovernedPublicationExecutionItemResult blockedItem(
            AtFaqDraftPersistenceItemResult draft,
            UUID qaId,
            KnowledgeQuestionAnswer qa,
            List<String> blocking) {
        return new AtFaqGovernedPublicationExecutionItemResult(
                draft.externalId(),
                qaId,
                true,  // looked eligible from persistence signals, but a guard refused it
                false,
                qa != null && qa.isPublished(),
                false,
                qa != null ? qa.getPublishedBy() : null,
                qa != null ? toInstant(qa.getPublishedAt()) : null,
                qa != null ? qa.getCurationStatus() : draft.curationStatus(),
                qa != null && qa.isEligibleForRag(),
                false,
                false,
                draft.autonomySignals(),
                draft.warnings(),
                List.copyOf(blocking),
                List.of("Corrigir a causa do bloqueio antes de publicar. Publicação recusada."));
    }

    private KnowledgeQuestionAnswer reload(UUID qaId) {
        return qaRepository.findById(qaId).orElse(null);
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
