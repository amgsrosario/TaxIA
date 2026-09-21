package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Aggregate counters for a governed AT-FAQ rollback run (Bloco E — E10A/E10B).
 *
 * <p>The caps are <b>cap-aware</b> and encode each frase-mestra structurally:
 * <ul>
 *   <li>E10A ({@code effectiveMaxItems == 1}) keeps the hard single-Q&amp;A cap: {@code rolledBack},
 *       {@code unpublished}, {@code deindexed} and {@code batchSize} are each 0 or 1 — "calar uma
 *       voz prova o travão";</li>
 *   <li>E10B ({@code effectiveMaxItems} in [2, {@value #MAX_SMALL_BATCH}]) raises that ceiling to the
 *       small-batch limit — "calar um pequeno coro prova a governação": more than one, but never
 *       more than three.</li>
 * </ul>
 * The counters track how many Q&amp;A had their RAG voice neutralized, never destruction of
 * knowledge. {@code skipped} always decomposes exactly into
 * {@code skippedAlreadyRolledBack + skippedNotIndexed + deferredDueToLimit}.
 *
 * @param totalIndexedItems       indexed items seen in the incoming E9A/E9B indexing result
 * @param eligibleForRollback     items that passed every rollback guard (rolled back or deferred)
 * @param rolledBack              Q&amp;A effectively rolled back in this run (≤ {@code effectiveMaxItems})
 * @param unpublished             Q&amp;A unpublished in this run (≤ {@code effectiveMaxItems})
 * @param deindexed               Q&amp;A whose embedding was removed in this run (≤ {@code effectiveMaxItems})
 * @param embeddingRowsBefore     embedding rows observed before rollback, summed over rolled-back Q&amp;A
 * @param embeddingRowsAfter      embedding rows observed after rollback, summed over rolled-back Q&amp;A (0)
 * @param ragRecoveredBefore      rolled-back Q&amp;A the RAG retrieved before the rollback
 * @param ragRecoveredAfter       rolled-back Q&amp;A the RAG still retrieves after the rollback (0)
 * @param skipped                 items skipped (already rolled back, not indexed, or deferred by the limit)
 * @param blocked                 items that looked rollback-able but failed a guard
 * @param requestedMaxItems       batch limit requested by the caller (1 for single mode)
 * @param effectiveMaxItems       batch limit actually applied after clamping (drives the caps above)
 * @param deferredDueToLimit      eligible items deferred because the batch limit was reached
 * @param skippedAlreadyRolledBack items skipped because they were already rolled back (idempotent)
 * @param skippedNotIndexed       items skipped because they were never indexed (nothing to revert)
 * @param batchSize               size of the coro effectively rolled back together (== {@code rolledBack})
 */
public record AtFaqRollbackTotals(
        int totalIndexedItems,
        int eligibleForRollback,
        int rolledBack,
        int unpublished,
        int deindexed,
        int embeddingRowsBefore,
        int embeddingRowsAfter,
        int ragRecoveredBefore,
        int ragRecoveredAfter,
        int skipped,
        int blocked,
        int requestedMaxItems,
        int effectiveMaxItems,
        int deferredDueToLimit,
        int skippedAlreadyRolledBack,
        int skippedNotIndexed,
        int batchSize) {

    /** Small-batch ceiling (E10B): never roll back more than three Q&amp;A in one governed run. */
    public static final int MAX_SMALL_BATCH = 3;

    public AtFaqRollbackTotals {
        if (effectiveMaxItems < 0 || effectiveMaxItems > MAX_SMALL_BATCH) {
            throw new IllegalArgumentException(
                    "effectiveMaxItems out of range [0, " + MAX_SMALL_BATCH + "]: " + effectiveMaxItems);
        }
        int cap = effectiveMaxItems;
        if (rolledBack < 0 || rolledBack > cap) {
            throw new IllegalArgumentException(
                    "rolledBack out of range (cap " + cap + "): rolledBack=" + rolledBack);
        }
        if (unpublished < 0 || unpublished > cap) {
            throw new IllegalArgumentException(
                    "unpublished out of range (cap " + cap + "): unpublished=" + unpublished);
        }
        if (deindexed < 0 || deindexed > cap) {
            throw new IllegalArgumentException(
                    "deindexed out of range (cap " + cap + "): deindexed=" + deindexed);
        }
        if (batchSize < 0 || batchSize > cap) {
            throw new IllegalArgumentException(
                    "batchSize out of range (cap " + cap + "): batchSize=" + batchSize);
        }
        if (totalIndexedItems < 0 || eligibleForRollback < 0 || embeddingRowsBefore < 0
                || embeddingRowsAfter < 0 || ragRecoveredBefore < 0 || ragRecoveredAfter < 0
                || skipped < 0 || blocked < 0 || requestedMaxItems < 0 || deferredDueToLimit < 0
                || skippedAlreadyRolledBack < 0 || skippedNotIndexed < 0) {
            throw new IllegalArgumentException("rollback counters must be >= 0");
        }
        if (skipped != skippedAlreadyRolledBack + skippedNotIndexed + deferredDueToLimit) {
            throw new IllegalArgumentException(
                    "skipped must decompose into alreadyRolledBack + notIndexed + deferredDueToLimit: "
                            + "skipped=" + skipped + ", alreadyRolledBack=" + skippedAlreadyRolledBack
                            + ", notIndexed=" + skippedNotIndexed + ", deferred=" + deferredDueToLimit);
        }
    }
}
