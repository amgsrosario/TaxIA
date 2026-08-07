package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Aggregate counters for a governed AT-FAQ publication dry-run (Bloco E — E8B.1).
 *
 * <p>The reconciliation invariant {@code eligibleForDryRunPublication + skipped + blocked ==
 * totalDrafts} always holds, and {@code simulated == eligibleForDryRunPublication}. The trailing
 * real-effect counters are hard zeros by construction: a dry-run persists, publishes and indexes
 * nothing, so {@code persisted == 0}, {@code published == 0}, {@code indexed == 0} and
 * {@code wouldIndex == 0}.
 *
 * @param totalDrafts                   number of input item results considered
 * @param eligibleForDryRunPublication  drafts that passed every dry-run guard
 * @param simulated                     drafts for which a simulated command was produced
 * @param skipped                       input items that were not materialized
 * @param blocked                       materialized items that failed a dry-run guard
 * @param wouldPersistKnowledgeQa       simulated commands that would persist a Q&A
 * @param wouldCreateSources            simulated commands that would create sources
 * @param wouldPublish                  simulated commands that would publish
 * @param wouldIndex                    always {@code 0} — indexation is never rehearsed
 * @param persisted                     always {@code 0} — nothing persisted
 * @param published                     always {@code 0} — nothing published
 * @param indexed                       always {@code 0} — nothing indexed
 */
public record AtFaqPublicationDryRunTotals(
        int totalDrafts,
        int eligibleForDryRunPublication,
        int simulated,
        int skipped,
        int blocked,
        int wouldPersistKnowledgeQa,
        int wouldCreateSources,
        int wouldPublish,
        int wouldIndex,
        int persisted,
        int published,
        int indexed) {
}
