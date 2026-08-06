package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * The kind of governed review decision a reviewer can take over a pre-curated AT-FAQ item
 * (Bloco E — E6).
 *
 * <p>"Rever não é publicar. É decidir o próximo portão." A review decision only decides the
 * <b>next gate</b> an item should go through. <b>None of these values publishes or indexes.</b>
 * There is no PUBLISH decision here: real governed publication stays out of scope (E7+).
 */
public enum AtFaqReviewDecisionType {

    /**
     * Accepts that the item is a candidate for a future automatic-but-controlled publication.
     * It does <b>not</b> publish and does <b>not</b> index — it only records that the item may
     * proceed to a later governed publication phase.
     */
    ACCEPT_AUTO_CONTROLLED_CANDIDATE,

    /** The item should follow an assisted-review path before any publication. */
    SEND_TO_ASSISTED_REVIEW,

    /** The item requires a mandatory manual review / parecer before any publication. */
    REQUIRE_MANUAL_REVIEW,

    /** The item must not proceed. */
    REJECT,

    /** Decision deferred for lack of elements. */
    DEFER
}
