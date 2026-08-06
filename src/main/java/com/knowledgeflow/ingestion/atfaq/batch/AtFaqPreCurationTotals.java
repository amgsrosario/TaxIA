package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Aggregate counters for a pre-curation result (Bloco E — E5).
 *
 * <p>There is no {@code published} and no {@code indexed} counter here by design: pre-curation
 * never publishes and never indexes.
 *
 * @param totalItems               items received
 * @param preCurated               items that reached a usable pre-curation proposal (RAW form present)
 * @param withTechnicalAnswer      items carrying a non-blank technical answer
 * @param withLegalReference       items with at least one legal reference
 * @param withOfficialSource       items with an official source
 * @param autoControlledCandidates items whose proposed path is AUTO_CONTROLLED
 * @param assistedCandidates       items whose proposed path is ASSISTED
 * @param manualRequiredCandidates items whose proposed path is MANUAL_REQUIRED
 * @param notPublishable           items whose proposed path is NOT_PUBLISHABLE
 * @param requiringReview          items needing review (proposed path != AUTO_CONTROLLED)
 * @param warnings                 total number of item-level warnings across the batch
 * @param conflicts                items flagged as conflict candidates
 * @param duplicates               items flagged as duplicate candidates
 */
public record AtFaqPreCurationTotals(
        int totalItems,
        int preCurated,
        int withTechnicalAnswer,
        int withLegalReference,
        int withOfficialSource,
        int autoControlledCandidates,
        int assistedCandidates,
        int manualRequiredCandidates,
        int notPublishable,
        int requiringReview,
        int warnings,
        int conflicts,
        int duplicates) {
}
