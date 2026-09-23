package com.knowledgeflow.ingestion.atfaq.pilot;

/**
 * The terminal outcome of a single {@link TaxiaPilotGovernedRunner} invocation (Bloco E, E9C).
 *
 * <p>These map directly to the mandated terminal lines (PASSO 18):
 * <ul>
 *   <li>{@code status} ends {@code READINESS=READY} or {@code READINESS=BLOCKED}
 *       ({@link #READY} / {@link #BLOCKED});</li>
 *   <li>{@code publish-one} ends {@code RESULT=PUBLISHED|BLOCKED|NO_CHANGE}
 *       ({@link #PUBLISHED} / {@link #BLOCKED} / {@link #NO_CHANGE});</li>
 *   <li>{@code rollback-one} ends {@code RESULT=ROLLED_BACK|BLOCKED|NO_CHANGE}
 *       ({@link #ROLLED_BACK} / {@link #BLOCKED} / {@link #NO_CHANGE}).</li>
 * </ul>
 *
 * <p>A blocked or no-change outcome is a normal, expected interface — never a stack trace.
 */
public enum PilotRunnerOutcome {

    /** {@code status}: base + target resolved; the runner could act. */
    READY,

    /** {@code publish-one}: the single target was published (indexed + marked published). */
    PUBLISHED,

    /** {@code rollback-one}: the single target was rolled back (unpublished + de-indexed). */
    ROLLED_BACK,

    /** A guard refused, or the target could not be uniquely resolved. Nothing was written. */
    BLOCKED,

    /** The target was already in the requested end state; the runner did nothing (idempotent). */
    NO_CHANGE
}
