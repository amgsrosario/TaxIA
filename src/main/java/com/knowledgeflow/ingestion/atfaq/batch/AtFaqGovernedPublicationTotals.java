package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Aggregate counters for a governed AT-FAQ publication plan (Bloco E — E7).
 *
 * <p>Invariants: {@code published == 0} and {@code indexed == 0} — a plan proves that nothing
 * passes without guards; it never publishes and never indexes. These two counters exist only to
 * make that invariant explicit and assertable.
 *
 * @param totalItems                number of plan candidates
 * @param readyForFuturePublication candidates ready for a future publication step (never published)
 * @param needsAssistedReview       candidates still requiring assisted review
 * @param needsManualReview         candidates requiring mandatory manual review
 * @param blocked                   candidates blocked
 * @param deferred                  candidates deferred
 * @param withTechnicalAnswer       candidates carrying a technical answer
 * @param withLegalReference        candidates carrying at least one legal reference
 * @param withSources               candidates carrying at least one proposed source
 * @param withWarnings              candidates with at least one guard warning
 * @param published                 always 0 (a plan never publishes)
 * @param indexed                   always 0 (a plan never indexes)
 */
public record AtFaqGovernedPublicationTotals(
        int totalItems,
        int readyForFuturePublication,
        int needsAssistedReview,
        int needsManualReview,
        int blocked,
        int deferred,
        int withTechnicalAnswer,
        int withLegalReference,
        int withSources,
        int withWarnings,
        int published,
        int indexed) {
}
