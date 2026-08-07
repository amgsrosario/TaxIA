package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.organizations.entity.Organization;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Governed persistence of AT-FAQ drafts into an (isolated) database (Bloco E — E8B.2).
 *
 * <p>"Persistir draft não é criar trabalho humano. É preparar conhecimento para publicação
 * governada, automática quando segura." Given the E8A {@link AtFaqMaterializationResult} and the
 * E8B.1 {@link AtFaqPublicationDryRunReport}, this service persists each draft that the dry-run
 * verified as eligible into a {@link KnowledgeQuestionAnswer} plus its
 * {@link KnowledgeSourceReference} rows, in the conservative {@link KnowledgeCurationStatus#IMPORTED}
 * state.
 *
 * <p>{@code IMPORTED} is used here as a <b>persisted curable draft, not as publishable
 * knowledge</b>: it is never validated, never published and never RAG-eligible. The service keeps
 * zero publication effects — it never sets {@code publishedAt}/{@code publishedBy}, never calls
 * {@code KnowledgeQuestionAnswerPublicationService}, never generates embeddings and never calls
 * {@code KnowledgeQaEmbeddingIndexerImpl}. Persistence is idempotent by
 * {@code (organization, sourceSystem, externalKey)}: re-running never duplicates a draft.
 *
 * <p>Each persisted draft is additionally classified for <i>future</i> safe automation (official
 * source, legal reference, technical answer, low risk, no conflicts, no blocking duplicate). This
 * classification prepares controlled auto-publication downstream; it publishes nothing here.
 */
@Service
public class AtFaqGovernedDraftPersistenceService {

    /** Source-system tag used for idempotent persistence of governed AT-FAQ drafts. */
    static final String SOURCE_SYSTEM = "at-faq-governed-batch";

    /**
     * Curation status written for every persisted draft. {@code IMPORTED} is the most conservative
     * existing status: not validated, not published, never RAG-eligible. It marks a persisted
     * curable draft, not publishable knowledge.
     */
    static final KnowledgeCurationStatus DRAFT_CURATION_STATUS = KnowledgeCurationStatus.IMPORTED;

    private final KnowledgeQuestionAnswerRepository qaRepository;
    private final KnowledgeSourceReferenceRepository sourceRepository;
    private final Clock clock;

    @Autowired
    public AtFaqGovernedDraftPersistenceService(
            KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeSourceReferenceRepository sourceRepository) {
        this(qaRepository, sourceRepository, Clock.systemUTC());
    }

    /** Test constructor: a fixed clock makes {@code persistedAt} deterministic. */
    AtFaqGovernedDraftPersistenceService(
            KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeSourceReferenceRepository sourceRepository,
            Clock clock) {
        this.qaRepository = qaRepository;
        this.sourceRepository = sourceRepository;
        this.clock = clock;
    }

    /**
     * Persists dry-run-verified drafts as curable {@code IMPORTED} knowledge, without publishing or
     * indexing. Only items materialized in E8A and marked eligible+simulated in E8B.1 are
     * persisted; everything else is skipped or blocked. Idempotent by
     * {@code (organization, sourceSystem, externalKey)}.
     */
    @Transactional
    public AtFaqDraftPersistenceResult persistDrafts(
            AtFaqMaterializationResult materializationResult,
            AtFaqPublicationDryRunReport dryRunReport,
            Organization organization,
            String persistedBy) {

        Instant persistedAt = clock.instant();
        String who = isNotBlank(persistedBy) ? persistedBy.strip() : "system";
        String batchId = materializationResult == null ? null : materializationResult.batchId();

        List<AtFaqMaterializationItemResult> inputs =
                materializationResult == null ? List.of() : materializationResult.itemResults();
        Map<String, AtFaqPublicationDryRunItemResult> dryRunByExternalId = indexDryRun(dryRunReport);

        List<AtFaqDraftPersistenceItemResult> itemResults = new ArrayList<>();
        int eligible = 0;
        int persisted = 0;
        int sourcesPersisted = 0;
        int skipped = 0;
        int blocked = 0;
        int autoFuture = 0;
        int humanLikely = 0;

        for (AtFaqMaterializationItemResult item : inputs) {
            AtFaqDraftPersistenceItemResult result = persistOne(
                    item, dryRunByExternalId.get(item == null ? null : item.externalId()), organization);
            itemResults.add(result);

            if (result.eligibleForPersistence()) {
                eligible++;
            }
            if (result.persisted()) {
                persisted++;
                if (result.sourcesPersisted()) {
                    sourcesPersisted++;
                }
            }
            if (result.eligibleForAutoPublicationFuture()) {
                autoFuture++;
            }
            if (result.requiresHumanIntervention()) {
                humanLikely++;
            }
            boolean isBlocked = !result.eligibleForPersistence() && !result.blockingReasons().isEmpty();
            if (isBlocked) {
                blocked++;
            } else if (!result.persisted()) {
                skipped++; // non-materialized input, or idempotent reuse of an already-persisted draft
            }
        }

        AtFaqDraftPersistenceTotals totals = new AtFaqDraftPersistenceTotals(
                inputs.size(), eligible, persisted, sourcesPersisted, skipped, blocked,
                autoFuture, humanLikely, 0 /* published */, 0 /* indexed */, 0 /* embeddings */);

        List<String> nextActions = List.of(
                "Rever drafts persistidos e, se aprovado, avançar para publicação governada real (E8B.3).",
                "Persistência apenas: nada foi publicado nem indexado (published=0, indexed=0, embeddings=0).");

        return new AtFaqDraftPersistenceResult(
                batchId, persistedAt, who, AtFaqDraftPersistenceMode.DRY_RUN_VERIFIED,
                totals, itemResults, List.of(), List.of(), nextActions);
    }

    private AtFaqDraftPersistenceItemResult persistOne(
            AtFaqMaterializationItemResult item,
            AtFaqPublicationDryRunItemResult dryRun,
            Organization organization) {

        if (item == null) {
            return blockedResult(null, null, List.of("Item de materialização ausente."));
        }

        // Not materialized: nothing to persist — skipped, not blocked.
        if (!item.materialized()) {
            return new AtFaqDraftPersistenceItemResult(
                    item.externalId(), false, false, false, false, false, null,
                    item.normalizedQuestion(), null, false, false,
                    List.of(), List.of(), List.of(),
                    List.of("Ignorado: item não foi materializado na E8A; nada a persistir."));
        }

        List<String> passed = new ArrayList<>();
        List<String> blocking = new ArrayList<>();
        AtFaqMaterializationCandidate draft = item.draft();

        // Cross-check with the DRY-RUN verdict (E8B.1).
        gate(passed, blocking, "dry-run-item-present", dryRun != null);
        if (dryRun != null) {
            gate(passed, blocking, "dry-run-eligible", dryRun.eligibleForDryRunPublication());
            gate(passed, blocking, "dry-run-simulated", dryRun.simulated());
            gate(passed, blocking, "dry-run-not-persisted", !dryRun.persisted());
            gate(passed, blocking, "dry-run-not-published", !dryRun.published());
            gate(passed, blocking, "dry-run-not-indexed", !dryRun.indexed());
            gate(passed, blocking, "dry-run-blocking-reasons-empty", dryRun.blockingReasons().isEmpty());
        }

        // Input item must not already carry a real effect.
        gate(passed, blocking, "input-not-persisted", !item.persisted());
        gate(passed, blocking, "input-not-published", !item.published());
        gate(passed, blocking, "input-not-indexed", !item.indexed());
        gate(passed, blocking, "input-has-no-knowledge-qa-id", item.knowledgeQaId() == null);
        gate(passed, blocking, "input-blocking-reasons-empty", item.blockingReasons().isEmpty());

        // Organization must exist.
        gate(passed, blocking, "organization-present", organization != null && organization.getId() != null);

        // The draft must carry every field a curable Q&A requires.
        gate(passed, blocking, "draft-present", draft != null);
        if (draft != null) {
            gate(passed, blocking, "external-id-present", isNotBlank(draft.externalId()));
            gate(passed, blocking, "normalized-question-present", isNotBlank(draft.normalizedQuestion()));
            gate(passed, blocking, "short-answer-present", isNotBlank(draft.shortAnswer()));
            gate(passed, blocking, "technical-answer-present", isNotBlank(draft.technicalAnswer()));
            gate(passed, blocking, "topic-present", draft.topic() != null);
            gate(passed, blocking, "risk-level-present", draft.riskLevel() != null);
            gate(passed, blocking, "jurisdiction-present", isNotBlank(draft.jurisdiction()));
            gate(passed, blocking, "at-least-one-source", !draft.sources().isEmpty());
            gate(passed, blocking, "at-least-one-official-source",
                    draft.sources().stream().anyMatch(AtFaqMaterializationSourceCandidate::official));
            gate(passed, blocking, "at-least-one-legal-reference", !draft.legalReferences().isEmpty());
        }

        if (!blocking.isEmpty()) {
            return blockedResult(item.externalId(), item.normalizedQuestion(), blocking);
        }

        // Idempotency: reuse an already-persisted draft instead of duplicating it.
        Optional<KnowledgeQuestionAnswer> existing = qaRepository
                .findByOrganizationIdAndSourceSystemAndExternalKey(
                        organization.getId(), SOURCE_SYSTEM, draft.externalId());
        if (existing.isPresent()) {
            KnowledgeQuestionAnswer qa = existing.get();
            AutonomyClassification autonomy = classifyAutonomy(draft);
            return new AtFaqDraftPersistenceItemResult(
                    draft.externalId(), true /* eligible */, false /* persisted (reused) */,
                    false, false, false, qa.getId(), draft.normalizedQuestion(),
                    qa.getCurationStatus(), autonomy.eligibleForAutoPublicationFuture(),
                    autonomy.requiresHumanIntervention(), autonomy.signals(),
                    List.of("Draft já existente para esta organização; reutilizado sem duplicar."),
                    List.of(),
                    List.of("Draft já persistido (idempotente). Nada foi publicado nem indexado."));
        }

        // Persist the curable draft (IMPORTED), then its sources. Never published, never indexed.
        KnowledgeQuestionAnswer qa = new KnowledgeQuestionAnswer(
                organization,
                draft.normalizedQuestion(),
                draft.technicalAnswer(),
                SOURCE_SYSTEM,
                draft.externalId());
        qa.updateCuration(
                draft.normalizedQuestion(),
                draft.shortAnswer(),
                draft.technicalAnswer(),
                draft.topic(),
                draft.subtopic(),
                draft.jurisdiction(),
                draft.riskLevel(),
                false /* requiresHumanValidation */,
                null /* validFrom */,
                null /* validTo */,
                "Draft governado E8B.2 — IMPORTED como rascunho curável persistido, não publicável.");
        KnowledgeQuestionAnswer savedQa = qaRepository.save(qa);

        for (AtFaqMaterializationSourceCandidate source : draft.sources()) {
            KnowledgeSourceReference ref = new KnowledgeSourceReference(
                    savedQa, source.type(), safeTitle(source.title()));
            ref.update(
                    source.type(),
                    safeTitle(source.title()),
                    source.legalReference(),
                    source.url(),
                    null /* documentId */,
                    null /* fragmentId */,
                    null /* validFrom */,
                    null /* validTo */,
                    null /* notes */);
            sourceRepository.save(ref);
        }

        AutonomyClassification autonomy = classifyAutonomy(draft);
        return new AtFaqDraftPersistenceItemResult(
                draft.externalId(),
                true /* eligibleForPersistence */,
                true /* persisted */,
                !draft.sources().isEmpty() /* sourcesPersisted */,
                false /* published */,
                false /* indexed */,
                savedQa.getId(),
                draft.normalizedQuestion(),
                savedQa.getCurationStatus(),
                autonomy.eligibleForAutoPublicationFuture(),
                autonomy.requiresHumanIntervention(),
                autonomy.signals(),
                List.of(),
                List.of(),
                List.of("Draft persistido como conhecimento curável (IMPORTED). "
                        + "Ainda não publicado nem indexado."));
    }

    /**
     * Classifies a draft for future safe automation. The draft qualifies for future controlled
     * auto-publication only when every governance signal is present: official source, legal
     * reference, technical answer, low risk, no conflicts and no blocking duplicate — the draft
     * having reached E8A/E8B.1 already implies the last two.
     */
    private AutonomyClassification classifyAutonomy(AtFaqMaterializationCandidate draft) {
        List<String> signals = new ArrayList<>();
        boolean officialSource =
                draft.sources().stream().anyMatch(AtFaqMaterializationSourceCandidate::official);
        boolean legalReference = !draft.legalReferences().isEmpty();
        boolean technicalAnswer = isNotBlank(draft.technicalAnswer());
        boolean lowRisk = draft.riskLevel() == KnowledgeRiskLevel.LOW;

        if (officialSource) signals.add("OFFICIAL_SOURCE_PRESENT");
        if (legalReference) signals.add("LEGAL_REFERENCE_PRESENT");
        if (technicalAnswer) signals.add("TECHNICAL_ANSWER_PRESENT");
        if (lowRisk) signals.add("LOW_RISK");
        // A draft only reaches persistence after E5/E6/E7/E8A/E8B.1 cleared conflicts/duplicates.
        signals.add("NO_CONFLICTS");
        signals.add("NO_BLOCKING_DUPLICATE");

        boolean autoEligible = officialSource && legalReference && technicalAnswer && lowRisk;
        if (autoEligible) {
            signals.add(0, "AUTO_PUBLICATION_FUTURE_ELIGIBLE");
        }
        return new AutonomyClassification(autoEligible, !autoEligible, List.copyOf(signals));
    }

    private static Map<String, AtFaqPublicationDryRunItemResult> indexDryRun(
            AtFaqPublicationDryRunReport dryRunReport) {
        Map<String, AtFaqPublicationDryRunItemResult> byId = new LinkedHashMap<>();
        if (dryRunReport != null) {
            for (AtFaqPublicationDryRunItemResult item : dryRunReport.itemResults()) {
                if (item != null && item.externalId() != null) {
                    byId.putIfAbsent(item.externalId(), item);
                }
            }
        }
        return byId;
    }

    private static AtFaqDraftPersistenceItemResult blockedResult(
            String externalId, String normalizedQuestion, List<String> blocking) {
        return new AtFaqDraftPersistenceItemResult(
                externalId, false, false, false, false, false, null,
                normalizedQuestion, null, false, false,
                List.of() /* autonomySignals */, List.of() /* warnings */, List.copyOf(blocking),
                List.of("Corrigir o draft antes de persistir. Persistência recusada."));
    }

    private static String safeTitle(String title) {
        if (!isNotBlank(title)) {
            return "Fonte AT FAQ";
        }
        String stripped = title.strip();
        return stripped.length() > 255 ? stripped.substring(0, 255) : stripped;
    }

    private static void gate(List<String> passed, List<String> blocking, String guard, boolean ok) {
        if (ok) {
            passed.add(guard);
        } else {
            blocking.add(guard);
        }
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** Internal carrier for the future-autonomy classification of a draft. */
    private record AutonomyClassification(
            boolean eligibleForAutoPublicationFuture,
            boolean requiresHumanIntervention,
            List<String> signals) {
    }
}
