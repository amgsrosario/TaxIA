package com.knowledgeflow.ingestion.atfaq.batch;

import java.util.List;

/**
 * A single governed review decision over a pre-curated AT-FAQ item (Bloco E — E6).
 *
 * <p>"Rever não é publicar. É decidir o próximo portão." This is the reviewer's input to
 * {@link AtFaqReviewService}: it decides the next gate for one item, it never publishes and
 * never indexes. Safe to log: {@code notes} must carry no raw HTML, no prompts and no chunks.
 *
 * @param externalId   external id of the pre-curated item this decision applies to
 * @param decisionType the kind of decision (see {@link AtFaqReviewDecisionType})
 * @param reviewer     reviewer identifier ("system", "test-reviewer" or a fictitious id in tests)
 * @param reason       justification; required except for {@link AtFaqReviewDecisionType#DEFER}
 *                     when enough notes are provided
 * @param notes        additional non-sensitive notes (no HTML, no prompts, no chunks)
 */
public record AtFaqReviewDecision(
        String externalId,
        AtFaqReviewDecisionType decisionType,
        String reviewer,
        String reason,
        List<String> notes) {

    public AtFaqReviewDecision {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** Convenience for a decision without extra notes. */
    public static AtFaqReviewDecision of(
            String externalId, AtFaqReviewDecisionType decisionType, String reviewer, String reason) {
        return new AtFaqReviewDecision(externalId, decisionType, reviewer, reason, List.of());
    }
}
