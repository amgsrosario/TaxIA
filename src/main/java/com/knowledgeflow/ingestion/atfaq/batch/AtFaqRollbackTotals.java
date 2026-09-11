package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Aggregate counters for a governed AT-FAQ rollback run (Bloco E — E10A).
 *
 * <p>The caps encode the frase-mestra structurally: E10A rolls back <b>at most one</b> Q&amp;A, so
 * {@code rolledBack}, {@code unpublished} and {@code deindexed} are each 0 or 1. Batch rollback is
 * E10B and out of scope here. "Retirar voz não é apagar conhecimento" — the counters track how many
 * Q&amp;A had their RAG voice neutralized, never destruction of knowledge.
 *
 * @param totalIndexedItems    indexed items seen in the incoming E9A/E9B indexing result
 * @param eligibleForRollback  items that passed every rollback guard
 * @param rolledBack           Q&amp;A effectively rolled back in this run (0 or 1)
 * @param unpublished          Q&amp;A unpublished in this run (0 or 1)
 * @param deindexed            Q&amp;A whose embedding was removed in this run (0 or 1)
 * @param embeddingRowsBefore  embedding rows observed before the rollback for the target Q&amp;A
 * @param embeddingRowsAfter   embedding rows observed after the rollback for the target Q&amp;A
 * @param ragRecoveredBefore   Q&amp;A the RAG retrieved before the rollback
 * @param ragRecoveredAfter    Q&amp;A the RAG still retrieves after the rollback (0 in the main test)
 * @param skipped              items skipped (already rolled back, or not indexed in the input)
 * @param blocked              items that looked rollback-able but failed a guard
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
        int blocked) {

    public AtFaqRollbackTotals {
        if (rolledBack < 0 || rolledBack > 1) {
            throw new IllegalArgumentException(
                    "E10A rolls back a single Q&A: rolledBack must be 0 or 1 (got " + rolledBack + ")");
        }
        if (unpublished < 0 || unpublished > 1) {
            throw new IllegalArgumentException(
                    "E10A unpublishes a single Q&A: unpublished must be 0 or 1 (got " + unpublished + ")");
        }
        if (deindexed < 0 || deindexed > 1) {
            throw new IllegalArgumentException(
                    "E10A deindexes a single Q&A: deindexed must be 0 or 1 (got " + deindexed + ")");
        }
        if (totalIndexedItems < 0 || eligibleForRollback < 0 || embeddingRowsBefore < 0
                || embeddingRowsAfter < 0 || ragRecoveredBefore < 0 || ragRecoveredAfter < 0
                || skipped < 0 || blocked < 0) {
            throw new IllegalArgumentException("rollback counters must be >= 0");
        }
    }
}
