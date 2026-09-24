package com.knowledgeflow.ingestion.atfaq.pilot;

/**
 * The terminal outcome of a single {@link TaxiaPilotOperations} invocation — Bloco E,
 * E9C-pilot-operations (PROMPT 100).
 *
 * <p>Every rendered result ends with the mandated terminal line {@code RESULT=<outcome>}. A blocked
 * or no-change outcome is a normal, expected interface — never a stack trace.
 */
public enum PilotOpsOutcome {

    /** {@code provision-actors}: at least one technical actor row was inserted. */
    PROVISIONED,

    /** {@code import-one}: exactly one new candidate Q&A was created (status IMPORTED). */
    IMPORTED,

    /** {@code curate-one}: the single target's curated fields were updated. */
    CURATED,

    /** {@code add-source-one}: exactly one source reference was added to the single target. */
    SOURCE_ADDED,

    /** {@code pending-review-one}: the single target moved to PENDING_REVIEW. */
    PENDING_REVIEW,

    /** {@code validate-one}: the single target moved to VALIDATED (no publish, no index). */
    VALIDATED,

    /** A guard refused, or the target could not be uniquely resolved. Nothing was written. */
    BLOCKED,

    /** The target was already in the requested end state; the tooling did nothing (idempotent). */
    NO_CHANGE
}
