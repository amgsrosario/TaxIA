package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import java.util.List;
import java.util.UUID;

/**
 * Per-draft outcome of governed AT-FAQ draft persistence (Bloco E — E8B.2).
 *
 * <p>Each input draft resolves to exactly one shape:
 * <ul>
 *   <li><b>persisted</b> — eligible and newly written ({@code persisted == true}, carries a
 *       {@code knowledgeQaId});</li>
 *   <li><b>skipped</b> — either not materialized, or already present in the database (idempotent
 *       reuse); {@code persisted == false};</li>
 *   <li><b>blocked</b> — materialized but failed a persistence guard.</li>
 * </ul>
 *
 * <p>Regardless of shape, {@code published == false} and {@code indexed == false} always hold: a
 * persisted draft is curable knowledge, never published or indexed. It stays at the conservative
 * {@link KnowledgeCurationStatus#IMPORTED} status and is therefore never RAG-eligible.
 *
 * @param externalId                     stable AT-FAQ identifier
 * @param eligibleForPersistence         whether every persistence guard passed
 * @param persisted                      whether a {@code KnowledgeQuestionAnswer} was newly written
 * @param sourcesPersisted               whether source references were written
 * @param published                      always {@code false}
 * @param indexed                        always {@code false}
 * @param knowledgeQaId                  id of the persisted (or reused) Q&A, or {@code null}
 * @param normalizedQuestion             normalized question text
 * @param curationStatus                 curation status of the persisted draft ({@code IMPORTED})
 * @param eligibleForAutoPublicationFuture whether this draft could later be auto-published if safe
 * @param requiresHumanIntervention      whether a human gate is likely required before publication
 * @param autonomySignals                audit signals feeding future safe-automation decisions
 * @param warnings                       non-blocking observations
 * @param blockingReasons                reasons persistence was refused (empty when persisted/reused)
 * @param nextActions                    recommended follow-ups
 */
public record AtFaqDraftPersistenceItemResult(
        String externalId,
        boolean eligibleForPersistence,
        boolean persisted,
        boolean sourcesPersisted,
        boolean published,
        boolean indexed,
        UUID knowledgeQaId,
        String normalizedQuestion,
        KnowledgeCurationStatus curationStatus,
        boolean eligibleForAutoPublicationFuture,
        boolean requiresHumanIntervention,
        List<String> autonomySignals,
        List<String> warnings,
        List<String> blockingReasons,
        List<String> nextActions) {

    public AtFaqDraftPersistenceItemResult {
        autonomySignals = autonomySignals == null ? List.of() : List.copyOf(autonomySignals);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
    }
}
