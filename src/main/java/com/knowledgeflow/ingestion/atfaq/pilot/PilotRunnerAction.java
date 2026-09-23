package com.knowledgeflow.ingestion.atfaq.pilot;

/**
 * The three — and only three — actions the guarded one-shot pilot runner accepts (Bloco E, E9C).
 *
 * <p>Each invocation of {@link TaxiaPilotGovernedRunner} performs exactly one of these against a
 * single explicit target and then terminates. There is no batch action, no "next", and no
 * auto-discovery.
 */
public enum PilotRunnerAction {

    /** Read-only preflight. Never writes; may run even with the E9C flag off. */
    STATUS,

    /** Publish (index + mark published) a single explicit Q&A. Requires the E9C flag. */
    PUBLISH_ONE,

    /** Roll back (unpublish + de-index) a single explicit Q&A. Requires the E9C flag. */
    ROLLBACK_ONE
}
