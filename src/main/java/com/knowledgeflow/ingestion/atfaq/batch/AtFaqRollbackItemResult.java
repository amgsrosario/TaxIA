package com.knowledgeflow.ingestion.atfaq.batch;

import java.util.List;
import java.util.UUID;

/**
 * Per-Q&amp;A outcome of a governed AT-FAQ rollback (Bloco E — E10A).
 *
 * <p>Frase-mestra: "Retirar voz não é apagar conhecimento. É neutralizar a recuperação preservando
 * rasto e motivo." At most one Q&amp;A resolves to {@code rolledBack == true}: the single
 * published/indexed case that passed every guard, was unpublished through the real
 * {@code KnowledgeQuestionAnswerPublicationService#unpublish}, had its embedding physically removed
 * ({@code embeddingRowsAfter == 0}) and is no longer retrieved by the RAG. A second run over the
 * same Q&amp;A is reported {@code rolledBack == false} with {@code alreadyRolledBack == true}
 * (idempotent, never an error); guard failures carry {@code blockingReasons}.
 *
 * <p>This record carries no raw vector, no passage/prompt text, no chunk and no HTML — only
 * governance-relevant state, the mandatory reason and the normalized question for traceability.
 *
 * @param externalId         stable AT-FAQ identifier
 * @param knowledgeQaId      id of the Q&amp;A, or {@code null}
 * @param eligibleForRollback whether every rollback guard passed
 * @param rolledBack         whether this Q&amp;A was effectively rolled back in this run
 * @param alreadyRolledBack  whether this Q&amp;A was already rolled back before this run (idempotent skip)
 * @param unpublished        whether {@code unpublish(...)} cleared the publication in this run
 * @param deindexed          whether the embedding was removed in this run
 * @param embeddingRemoved   alias of {@code deindexed}: the embedding row no longer exists
 * @param ragRecoveredBefore whether the RAG retrieved this Q&amp;A before the rollback
 * @param ragRecoveredAfter  whether the RAG still retrieves this Q&amp;A after the rollback
 * @param embeddingRowsBefore embedding rows observed before the rollback (1 for an eligible item)
 * @param embeddingRowsAfter  embedding rows observed after the rollback (0 when rolled back)
 * @param publishedBefore    whether the Q&amp;A was published before the rollback
 * @param publishedAfter     whether the Q&amp;A is published after the rollback
 * @param reason             the mandatory rollback reason carried through from the command
 * @param warnings           non-blocking observations
 * @param blockingReasons    reasons rollback was refused (empty when rolled back / skipped-clean)
 * @param nextActions        recommended follow-ups
 * @param skippedAlreadyRolledBack whether this run skipped the item because it was already rolled back
 *                                 (equals {@code alreadyRolledBack}; idempotent skip)
 * @param skippedNotIndexed  whether this run skipped the item because it was never indexed (E10B batch)
 * @param deferredDueToLimit whether this eligible item was deferred because the batch limit was reached
 * @param batchPosition      1-based position within the small batch (1 in single mode; 0 when not applicable)
 */
public record AtFaqRollbackItemResult(
        String externalId,
        UUID knowledgeQaId,
        boolean eligibleForRollback,
        boolean rolledBack,
        boolean alreadyRolledBack,
        boolean unpublished,
        boolean deindexed,
        boolean embeddingRemoved,
        boolean ragRecoveredBefore,
        boolean ragRecoveredAfter,
        int embeddingRowsBefore,
        int embeddingRowsAfter,
        boolean publishedBefore,
        boolean publishedAfter,
        String reason,
        List<String> warnings,
        List<String> blockingReasons,
        List<String> nextActions,
        boolean skippedAlreadyRolledBack,
        boolean skippedNotIndexed,
        boolean deferredDueToLimit,
        int batchPosition) {

    public AtFaqRollbackItemResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
        if (embeddingRowsBefore < 0 || embeddingRowsAfter < 0) {
            throw new IllegalArgumentException(
                    "embedding row counts must be >= 0 for " + externalId);
        }
        if (batchPosition < 0) {
            throw new IllegalArgumentException("batchPosition must be >= 0 for " + externalId);
        }
        if (deindexed != embeddingRemoved) {
            throw new IllegalArgumentException(
                    "deindexed and embeddingRemoved must agree for " + externalId);
        }
        if (skippedAlreadyRolledBack != alreadyRolledBack) {
            throw new IllegalArgumentException(
                    "skippedAlreadyRolledBack must agree with alreadyRolledBack for " + externalId);
        }
        if (skippedNotIndexed && eligibleForRollback) {
            throw new IllegalArgumentException(
                    "a not-indexed skip cannot be eligible for rollback: " + externalId);
        }
        if (rolledBack && (skippedAlreadyRolledBack || skippedNotIndexed || deferredDueToLimit)) {
            throw new IllegalArgumentException(
                    "a rolled-back Q&A cannot also be skipped or deferred: " + externalId);
        }
        if (rolledBack) {
            if (!unpublished) {
                throw new IllegalArgumentException("A rolled-back Q&A must be unpublished: " + externalId);
            }
            if (!deindexed) {
                throw new IllegalArgumentException("A rolled-back Q&A must be deindexed: " + externalId);
            }
            if (!ragRecoveredBefore) {
                throw new IllegalArgumentException(
                        "A rolled-back Q&A must have been RAG-recoverable before: " + externalId);
            }
            if (ragRecoveredAfter) {
                throw new IllegalArgumentException(
                        "A rolled-back Q&A must not be RAG-recoverable after: " + externalId);
            }
            if (embeddingRowsBefore != 1) {
                throw new IllegalArgumentException(
                        "A rolled-back Q&A must have had exactly one embedding row: " + externalId);
            }
            if (embeddingRowsAfter != 0) {
                throw new IllegalArgumentException(
                        "A rolled-back Q&A must end with zero embedding rows: " + externalId);
            }
            if (!publishedBefore) {
                throw new IllegalArgumentException(
                        "A rolled-back Q&A must have been published before: " + externalId);
            }
            if (publishedAfter) {
                throw new IllegalArgumentException(
                        "A rolled-back Q&A must not be published after: " + externalId);
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException(
                        "A rolled-back Q&A must carry a non-blank reason: " + externalId);
            }
        }
    }
}
