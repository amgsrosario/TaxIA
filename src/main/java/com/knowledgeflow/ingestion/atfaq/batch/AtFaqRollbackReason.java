package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Closed taxonomy of governed AT-FAQ rollback reasons (Bloco E — E10-policy-impl).
 *
 * <p>Approved on the E10-policy decisions (PROMPT 75/76, decision D4): the free-text rollback reason
 * is replaced by this closed set so every governed rollback is traceable by a stable code. The code
 * is <b>governance and traceability only</b> — it is persisted in the audit log (decision D2) and it
 * never, in this phase, authorizes real automatic execution.
 *
 * <p>Two reasons are deliberately kept distinct and must never be merged:
 * <ul>
 *   <li>{@link #SOURCE_OUTDATED}: a more recent version / superseding source exists — the knowledge
 *       is stale because the underlying source moved on;</li>
 *   <li>{@link #LEGAL_CHANGE}: a material legal/regulatory change occurred that may alter the
 *       fiscal/juridical conclusion itself — not merely a newer edition of the same source.</li>
 * </ul>
 *
 * <p>{@link #requiresDetail()} is {@code true} only for {@link #OTHER}, which always demands a
 * non-blank complementary text. {@link #higherSeverity()} flags the graver reasons
 * ({@link #LEGAL_CHANGE}, {@link #SECURITY_OR_COMPLIANCE}, {@link #CONTENT_ERROR},
 * {@link #SOURCE_INVALIDATED}); those must not be treated as an authorization for real automatic
 * execution — the flag is a governance signal, never an automation switch in this phase.
 */
public enum AtFaqRollbackReason {

    /** The underlying source is no longer valid (e.g. revoked ruling): graver, never auto in this phase. */
    SOURCE_INVALIDATED(false, true),

    /** A more recent version / superseding source exists — distinct from a material legal change. */
    SOURCE_OUTDATED(false, false),

    /** The published answer is factually wrong: graver, never auto in this phase. */
    CONTENT_ERROR(false, true),

    /** Curation error (wrong topic/risk/jurisdiction), the content itself may be sound. */
    CURATION_ERROR(false, false),

    /** Superseded by an equivalent/better Q&amp;A already published. */
    DUPLICATE_OR_SUPERSEDED(false, false),

    /** A material legal/regulatory change may alter the fiscal conclusion: graver, never auto. */
    LEGAL_CHANGE(false, true),

    /** Published by mistake / prematurely; the content was not necessarily wrong. */
    PUBLICATION_ERROR(false, false),

    /** Data protection, compliance or confidentiality risk: graver, never auto in this phase. */
    SECURITY_OR_COMPLIANCE(false, true),

    /** Deliberate manual correction by an authorized operator. */
    MANUAL_CORRECTION(false, false),

    /** Controlled escape hatch: always requires a non-blank complementary detail. */
    OTHER(true, false);

    private final boolean requiresDetail;
    private final boolean higherSeverity;

    AtFaqRollbackReason(boolean requiresDetail, boolean higherSeverity) {
        this.requiresDetail = requiresDetail;
        this.higherSeverity = higherSeverity;
    }

    /** Whether this reason always demands a non-blank complementary detail ({@link #OTHER} only). */
    public boolean requiresDetail() {
        return requiresDetail;
    }

    /**
     * Whether this is one of the graver reasons that must never be treated as an authorization for
     * real automatic execution. Governance signal only — this phase wires no automation to it.
     */
    public boolean higherSeverity() {
        return higherSeverity;
    }
}
