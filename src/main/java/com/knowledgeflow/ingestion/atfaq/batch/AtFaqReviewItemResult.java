package com.knowledgeflow.ingestion.atfaq.batch;

import java.util.List;

/**
 * The outcome of applying a governed review decision to one pre-curated AT-FAQ item
 * (Bloco E — E6).
 *
 * <p>This records the <b>next gate</b> decided for the item, not a publication. In particular,
 * {@code acceptedForFuturePublication} does <b>not</b> mean published: it means only that the
 * item may proceed to a later governed publication phase (E7+). Nothing here is published or
 * indexed. Safe to log: no raw HTML, no prompts, no chunks.
 *
 * @param externalId                   external id of the item
 * @param normalizedQuestion           whitespace-stable question (carried from pre-curation)
 * @param proposedPublicationPath      the path E5 proposed for this item
 * @param decisionType                 the reviewer's decision (or a defaulted DEFER when missing)
 * @param resultingPath                the future path after the decision (never a publication)
 * @param acceptedForFuturePublication may proceed to future governed publication (never published now)
 * @param requiresHumanReview          the item still needs assisted or manual human review
 * @param rejected                     the item must not proceed
 * @param deferred                     the decision was deferred / missing / lacked elements
 * @param reasons                      reasons supporting the outcome
 * @param warnings                     non-blocking notes
 * @param blockingErrors               blocking problems (e.g. an invalid prudence-relaxing decision)
 */
public record AtFaqReviewItemResult(
        String externalId,
        String normalizedQuestion,
        AtFaqBatchPublicationPath proposedPublicationPath,
        AtFaqReviewDecisionType decisionType,
        AtFaqBatchPublicationPath resultingPath,
        boolean acceptedForFuturePublication,
        boolean requiresHumanReview,
        boolean rejected,
        boolean deferred,
        List<String> reasons,
        List<String> warnings,
        List<String> blockingErrors) {

    public AtFaqReviewItemResult {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        blockingErrors = blockingErrors == null ? List.of() : List.copyOf(blockingErrors);
    }
}
