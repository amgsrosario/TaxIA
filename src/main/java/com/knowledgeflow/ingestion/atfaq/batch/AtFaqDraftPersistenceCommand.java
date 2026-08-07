package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import java.util.List;

/**
 * The governed persistence intent for a single AT-FAQ draft (Bloco E — E8B.2).
 *
 * <p>Describes what the persistence run does for one draft: which conservative curation status it
 * writes ({@code IMPORTED}), whether it persists the {@code KnowledgeQuestionAnswer} and its
 * sources. It carries the hard invariants that persisting is not publishing: {@link #publish()} and
 * {@link #index()} are always {@code false}.
 *
 * @param externalId             stable AT-FAQ identifier of the draft
 * @param normalizedQuestion     normalized question text of the draft
 * @param intendedCurationStatus curation status actually written — always {@code IMPORTED}
 * @param persistKnowledgeQa     whether a {@code KnowledgeQuestionAnswer} is persisted
 * @param persistSources         whether source references are persisted
 * @param publish                always {@code false} — persistence never publishes
 * @param index                  always {@code false} — persistence never indexes
 * @param guardChecks            names of the persistence guards that were evaluated and passed
 * @param warnings               non-blocking observations
 * @param blockingReasons        reasons persistence was refused (empty when eligible)
 */
public record AtFaqDraftPersistenceCommand(
        String externalId,
        String normalizedQuestion,
        KnowledgeCurationStatus intendedCurationStatus,
        boolean persistKnowledgeQa,
        boolean persistSources,
        boolean publish,
        boolean index,
        List<String> guardChecks,
        List<String> warnings,
        List<String> blockingReasons) {

    public AtFaqDraftPersistenceCommand {
        guardChecks = guardChecks == null ? List.of() : List.copyOf(guardChecks);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
    }
}
