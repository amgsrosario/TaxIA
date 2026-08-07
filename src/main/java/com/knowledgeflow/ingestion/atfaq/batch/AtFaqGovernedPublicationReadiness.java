package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Readiness of a pre-curated + reviewed AT-FAQ item for a <b>future</b> governed publication
 * (Bloco E — E7).
 *
 * <p>"Planear publicação não é publicar. É provar que nada passa sem guardas." This records how
 * far an item may proceed towards a later, real publication step. <b>None of these values
 * publishes or indexes.</b> Even {@link #READY_FOR_FUTURE_PUBLICATION} only means the item passed
 * the plan's guards — the actual PUBLISH_GOVERNED step stays out of scope (E8+).
 */
public enum AtFaqGovernedPublicationReadiness {

    /** Passed every plan guard; may proceed to a future real publication step. Not published. */
    READY_FOR_FUTURE_PUBLICATION,

    /** Still requires assisted review before it could be considered for publication. */
    NEEDS_ASSISTED_REVIEW,

    /** Requires a mandatory manual review / parecer before any publication. */
    NEEDS_MANUAL_REVIEW,

    /** Cannot proceed (missing technical answer/source/question, conflict, outdated, blocking error). */
    BLOCKED,

    /** Decision deferred or information insufficient. */
    DEFERRED
}
