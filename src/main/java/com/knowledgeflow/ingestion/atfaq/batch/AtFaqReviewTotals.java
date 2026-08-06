package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Aggregate counters for a governed AT-FAQ review (Bloco E — E6).
 *
 * <p>Invariants: {@code published == 0} and {@code indexed == 0} — a review decides gates, it
 * never publishes and never indexes. These two counters exist only to make that invariant
 * explicit and assertable.
 *
 * @param totalItems                       number of pre-curated items reviewed
 * @param decisionsProvided                number of items with an explicit decision supplied
 * @param acceptedAutoControlledCandidates items accepted as future auto-controlled candidates
 * @param sentToAssistedReview             items routed to assisted review
 * @param manualReviewRequired             items requiring mandatory manual review
 * @param rejected                         items rejected
 * @param deferred                         items deferred
 * @param missingDecision                  items without a supplied decision
 * @param blocked                          items with at least one blocking error
 * @param warnings                         total number of item-level warnings
 * @param published                        always 0 (a review never publishes)
 * @param indexed                          always 0 (a review never indexes)
 */
public record AtFaqReviewTotals(
        int totalItems,
        int decisionsProvided,
        int acceptedAutoControlledCandidates,
        int sentToAssistedReview,
        int manualReviewRequired,
        int rejected,
        int deferred,
        int missingDecision,
        int blocked,
        int warnings,
        int published,
        int indexed) {
}
