package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Aggregate counters for a governed AT-FAQ publication run (Bloco E — E8B.3).
 *
 * <p>Named with the {@code Execution} infix to distinguish the E8B.3 execution artifacts from the
 * E7 {@code AtFaqGovernedPublicationTotals} of the publication plan.
 *
 * <p>The counters double as executable guarantees. Three of them are hard-zero invariants that
 * encode the frase-mestra "publicar em teste não é dar voz ao conhecimento": no matter how many
 * drafts are published, {@code indexed}, {@code embeddings} and {@code ragExpected} must all stay
 * at zero, because publication runs under the no-op stub indexer.
 *
 * @param totalPersistedDrafts       persisted drafts considered by this run
 * @param eligibleForGovernedPublication drafts that passed every governed-publication guard
 * @param validated                  drafts promoted IMPORTED → VALIDATED in this run
 * @param published                  drafts published via the real service in this run
 * @param skipped                    drafts intentionally not published (not eligible, or already published)
 * @param blocked                    drafts refused by a publication guard
 * @param indexed                    always {@code 0}
 * @param embeddings                 always {@code 0}
 * @param ragExpected                always {@code 0}
 * @param humanInterventionRequired  drafts still flagged as needing a human gate
 */
public record AtFaqGovernedPublicationExecutionTotals(
        int totalPersistedDrafts,
        int eligibleForGovernedPublication,
        int validated,
        int published,
        int skipped,
        int blocked,
        int indexed,
        int embeddings,
        int ragExpected,
        int humanInterventionRequired) {

    public AtFaqGovernedPublicationExecutionTotals {
        if (indexed != 0) {
            throw new IllegalArgumentException("E8B.3 invariant violated: indexed must be 0");
        }
        if (embeddings != 0) {
            throw new IllegalArgumentException("E8B.3 invariant violated: embeddings must be 0");
        }
        if (ragExpected != 0) {
            throw new IllegalArgumentException("E8B.3 invariant violated: ragExpected must be 0");
        }
    }
}
