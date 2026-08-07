package com.knowledgeflow.ingestion.atfaq.batch;

import java.util.List;
import java.util.UUID;

/**
 * Per-draft outcome of the governed AT-FAQ publication dry-run (Bloco E — E8B.1).
 *
 * <p>The rehearsal produces one of three shapes per input draft:
 * <ul>
 *   <li><b>simulated</b> — {@code eligibleForDryRunPublication == true} and {@code simulated == true},
 *       carrying a {@link AtFaqPublicationDryRunCommand};</li>
 *   <li><b>skipped</b> — the input item was not materialized, so there is nothing to rehearse;</li>
 *   <li><b>blocked</b> — the item was materialized but failed a dry-run guard.</li>
 * </ul>
 *
 * <p>Regardless of shape, the invariants {@code persisted == false}, {@code published == false},
 * {@code indexed == false} and {@code knowledgeQaId == null} always hold: the dry-run has zero real
 * effects.
 *
 * @param externalId                   stable AT-FAQ identifier
 * @param eligibleForDryRunPublication whether every dry-run guard passed
 * @param simulated                    whether a simulated command was produced
 * @param persisted                    always {@code false} — nothing is persisted
 * @param published                    always {@code false} — nothing is published
 * @param indexed                      always {@code false} — nothing is indexed
 * @param knowledgeQaId                always {@code null} — no {@code KnowledgeQuestionAnswer} exists
 * @param normalizedQuestion           normalized question text
 * @param command                      the simulated command, or {@code null} when skipped/blocked
 * @param warnings                     non-blocking observations
 * @param blockingReasons              reasons the rehearsal refused this draft (empty when simulated)
 * @param nextActions                  recommended follow-ups
 */
public record AtFaqPublicationDryRunItemResult(
        String externalId,
        boolean eligibleForDryRunPublication,
        boolean simulated,
        boolean persisted,
        boolean published,
        boolean indexed,
        UUID knowledgeQaId,
        String normalizedQuestion,
        AtFaqPublicationDryRunCommand command,
        List<String> warnings,
        List<String> blockingReasons,
        List<String> nextActions) {

    public AtFaqPublicationDryRunItemResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
    }
}
