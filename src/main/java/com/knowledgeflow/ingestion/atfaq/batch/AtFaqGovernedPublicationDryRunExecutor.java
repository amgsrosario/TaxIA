package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * DRY-RUN executor for governed AT-FAQ publication (Bloco E — E8B.1).
 *
 * <p>"Ensaiar publicação não é publicar. É validar que o executor respeita os guardas antes de
 * tocar na BD." Given an E8A {@link AtFaqMaterializationResult}, this service rehearses the future
 * publication of each materialized draft: it re-validates the publication guards using the draft
 * carried on each item, and — when they pass — builds a simulated {@link AtFaqPublicationDryRunCommand}
 * describing what a real run <i>would</i> do. It never publishes.
 *
 * <p>It keeps <b>zero real effects</b>. It does not persist a {@code KnowledgeQuestionAnswer} or a
 * {@code KnowledgeSourceReference}, it does not call {@code KnowledgeQuestionAnswerPublicationService},
 * it does not call {@code KnowledgeQaEmbeddingIndexerImpl}, it produces no embeddings and touches no
 * real data, migration, endpoint or provider. Every produced report keeps {@code persisted == 0},
 * {@code published == 0}, {@code indexed == 0} and {@code wouldIndex == 0}. The rehearsal is
 * deterministic and idempotent: the same input and clock yield an equal report.
 */
@Service
public class AtFaqGovernedPublicationDryRunExecutor {

    /** Symbolic action a real governed publication would perform. Never executed here. */
    static final String INTENDED_ACTION = "PUBLISH_GOVERNED_DRY_RUN";

    /**
     * Curation status a real publication would target. Declared as intent only; never applied to any
     * entity by the dry-run. The materialized draft itself stays at its conservative
     * {@link KnowledgeCurationStatus#IMPORTED} status.
     */
    static final KnowledgeCurationStatus INTENDED_CURATION_STATUS = KnowledgeCurationStatus.VALIDATED;

    private final Clock clock;

    @Autowired
    public AtFaqGovernedPublicationDryRunExecutor() {
        this(Clock.systemUTC());
    }

    /** Test constructor: a fixed clock makes {@code executedAt} deterministic. */
    AtFaqGovernedPublicationDryRunExecutor(Clock clock) {
        this.clock = clock;
    }

    /**
     * Rehearses governed publication over an E8A materialization result. Only materialized drafts are
     * considered; each is re-validated against the dry-run guards and, when eligible, turned into a
     * simulated command. Nothing is persisted, published or indexed.
     */
    public AtFaqPublicationDryRunReport dryRun(
            AtFaqMaterializationResult materializationResult, String executedBy) {

        Instant executedAt = clock.instant();
        String who = isNotBlank(executedBy) ? executedBy.strip() : "system";
        String batchId = materializationResult == null ? null : materializationResult.batchId();
        List<AtFaqMaterializationItemResult> inputs =
                materializationResult == null ? List.of() : materializationResult.itemResults();

        List<AtFaqPublicationDryRunItemResult> itemResults = new ArrayList<>();
        int eligible = 0;
        int skipped = 0;
        int blocked = 0;
        int wouldPersist = 0;
        int wouldCreateSources = 0;
        int wouldPublish = 0;

        for (AtFaqMaterializationItemResult item : inputs) {
            AtFaqPublicationDryRunItemResult result = rehearse(item);
            itemResults.add(result);
            if (result.simulated()) {
                eligible++;
                if (result.command() != null) {
                    if (result.command().wouldPersistKnowledgeQa()) {
                        wouldPersist++;
                    }
                    if (result.command().wouldCreateSources()) {
                        wouldCreateSources++;
                    }
                    if (result.command().wouldPublish()) {
                        wouldPublish++;
                    }
                }
            } else if (result.blockingReasons().isEmpty()) {
                skipped++;
            } else {
                blocked++;
            }
        }

        AtFaqPublicationDryRunTotals totals = new AtFaqPublicationDryRunTotals(
                inputs.size(), eligible, eligible /* simulated */, skipped, blocked,
                wouldPersist, wouldCreateSources, wouldPublish,
                0 /* wouldIndex */, 0 /* persisted */, 0 /* published */, 0 /* indexed */);

        List<String> nextActions = List.of(
                "Rever o relatório e, se aprovado, avançar para publicação governada real isolada (E8B.2).",
                "Ensaio apenas: nada foi persistido, publicado nem indexado (persisted=0, published=0, indexed=0).");

        return new AtFaqPublicationDryRunReport(
                batchId, executedAt, who, AtFaqPublicationDryRunMode.DRY_RUN_ONLY,
                totals, itemResults, List.of(), List.of(), nextActions);
    }

    private AtFaqPublicationDryRunItemResult rehearse(AtFaqMaterializationItemResult item) {
        if (item == null) {
            return new AtFaqPublicationDryRunItemResult(
                    null, false, false, false, false, false, null, null, null,
                    List.of(), List.of("Item de materialização ausente."), List.of());
        }

        // Not materialized: nothing to rehearse — skipped, not blocked.
        if (!item.materialized()) {
            return new AtFaqPublicationDryRunItemResult(
                    item.externalId(), false, false, false, false, false, null,
                    item.normalizedQuestion(), null,
                    List.of(), List.of(),
                    List.of("Ignorado: item não foi materializado na E8A; nada a ensaiar."));
        }

        List<String> passed = new ArrayList<>();
        List<String> blocking = new ArrayList<>();
        AtFaqMaterializationCandidate draft = item.draft();

        // Zero-effect invariants of the input item: a materialized draft must not already carry any
        // real effect. If it does, refuse to rehearse it — the pipeline upstream is inconsistent.
        gate(passed, blocking, "input-not-persisted", !item.persisted());
        gate(passed, blocking, "input-not-published", !item.published());
        gate(passed, blocking, "input-not-indexed", !item.indexed());
        gate(passed, blocking, "input-has-no-knowledge-qa-id", item.knowledgeQaId() == null);
        gate(passed, blocking, "input-blocking-reasons-empty", item.blockingReasons().isEmpty());

        // The draft must exist and carry every field a future governed publication would require.
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
            return new AtFaqPublicationDryRunItemResult(
                    item.externalId(), false, false, false, false, false, null,
                    item.normalizedQuestion(), null,
                    List.of(), List.copyOf(blocking),
                    List.of("Corrigir o rascunho antes de ensaiar publicação. Ensaio recusado."));
        }

        boolean wouldCreateSources = !draft.sources().isEmpty();
        AtFaqPublicationDryRunCommand command = new AtFaqPublicationDryRunCommand(
                draft.externalId(),
                draft.normalizedQuestion(),
                INTENDED_ACTION,
                INTENDED_CURATION_STATUS,
                true /* wouldPersistKnowledgeQa */,
                wouldCreateSources,
                true /* wouldPublish */,
                false /* wouldIndex — indexação é E9, nunca ensaiada */,
                List.copyOf(passed),
                List.of(),
                List.of());

        return new AtFaqPublicationDryRunItemResult(
                item.externalId(),
                true /* eligibleForDryRunPublication */,
                true /* simulated */,
                false /* persisted */,
                false /* published */,
                false /* indexed */,
                null /* knowledgeQaId — nada persistido */,
                draft.normalizedQuestion(),
                command,
                List.of(),
                List.of(),
                List.of("Ensaio aprovado: publicação governada real poderá seguir em E8B.2. "
                        + "Ainda não publicado nem indexado."));
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
}
