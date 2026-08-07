package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Aggregate counters for a governed AT-FAQ draft persistence run (Bloco E — E8B.2).
 *
 * <p>The reconciliation invariant {@code persisted + skipped + blocked == totalDrafts} always
 * holds; {@code skipped} absorbs both non-materialized inputs and idempotent reuses. The trailing
 * counters are hard zeros by construction: persistence publishes nothing, indexes nothing and
 * generates no embeddings, so {@code published == 0}, {@code indexed == 0} and
 * {@code embeddings == 0}.
 *
 * @param totalDrafts                    number of input drafts considered
 * @param eligibleForPersistence         drafts that passed every persistence guard
 * @param persisted                      drafts newly written as {@code KnowledgeQuestionAnswer}
 * @param sourcesPersisted               drafts whose source references were written
 * @param skipped                        non-materialized inputs plus idempotent reuses
 * @param blocked                        materialized drafts that failed a persistence guard
 * @param autoPublicationFutureEligible  drafts that could later be auto-published if safe
 * @param humanInterventionLikely        drafts likely to require a human gate before publication
 * @param published                      always {@code 0} — nothing published
 * @param indexed                        always {@code 0} — nothing indexed
 * @param embeddings                     always {@code 0} — no embeddings generated
 */
public record AtFaqDraftPersistenceTotals(
        int totalDrafts,
        int eligibleForPersistence,
        int persisted,
        int sourcesPersisted,
        int skipped,
        int blocked,
        int autoPublicationFutureEligible,
        int humanInterventionLikely,
        int published,
        int indexed,
        int embeddings) {
}
