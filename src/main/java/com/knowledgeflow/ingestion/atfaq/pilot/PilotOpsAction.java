package com.knowledgeflow.ingestion.atfaq.pilot;

/**
 * The six — and only six — actions the guarded one-shot pilot <b>operations</b> tooling accepts —
 * Bloco E, E9C-pilot-operations (PROMPT 100).
 *
 * <p>These cover strictly the <em>preparation</em> of an N=1 candidate up to (and including) the
 * VALIDATED state. Publication, rollback and status deliberately live elsewhere, in
 * {@link TaxiaPilotGovernedRunner} — the tooling here <b>never</b> publishes, indexes, embeds or
 * rolls back. Each invocation of {@link TaxiaPilotOperations} performs exactly one of these against a
 * single explicit target and then terminates: there is no batch action, no "next", no auto-discovery.
 */
public enum PilotOpsAction {

    /** Idempotently provision the two dedicated non-login technical actors (publisher + rollback). */
    PROVISION_ACTORS,

    /** Introduce exactly one candidate Q&A (status IMPORTED) under an explicit source system. */
    IMPORT_ONE,

    /** Curate exactly one explicit Q&A (short/technical answer, classification) — merge, never wipe. */
    CURATE_ONE,

    /** Add exactly one official source reference to a single explicit Q&A. */
    ADD_SOURCE_ONE,

    /** Move a single explicit Q&A from IMPORTED/NEEDS_UPDATE to PENDING_REVIEW. */
    PENDING_REVIEW_ONE,

    /** Validate a single explicit Q&A (PENDING_REVIEW → VALIDATED). Never publishes or indexes. */
    VALIDATE_ONE
}
