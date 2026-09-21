package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Governed rollback motive: a mandatory taxonomy {@link AtFaqRollbackReason code} plus an optional
 * complementary {@code detail} (Bloco E — E10-policy-impl).
 *
 * <p>Replaces the previous free-text reason (decision D4). Validation rules:
 * <ul>
 *   <li>{@code code} is mandatory — never {@code null};</li>
 *   <li>{@code detail} is trimmed; a blank/whitespace-only detail is normalized to {@code null};</li>
 *   <li>{@code detail} is optional for most reasons, but <b>mandatory</b> for
 *       {@link AtFaqRollbackReason#OTHER}.</li>
 * </ul>
 * No arbitrary length limit is imposed here: the audit sink stores the motive in a {@code TEXT}
 * column ({@code audit_events.metadata}), so there is no schema limit to respect and none is invented.
 *
 * <p>{@link #auditDetail()} renders a stable, human-readable governance string reusing the project's
 * existing {@code key=value} audit-metadata convention (as in {@code publisher=...},
 * {@code previousVersionId=...}): {@code reasonCode=<CODE>} optionally followed by
 * {@code ; reasonDetail=<detail>}. This is the exact string persisted in the
 * {@code KNOWLEDGE_QA_UNPUBLISHED} audit event and carried through the rollback report.
 *
 * @param code   mandatory rollback reason from the closed taxonomy
 * @param detail optional complementary text (mandatory for {@link AtFaqRollbackReason#OTHER}),
 *               normalized to {@code null} when blank
 */
public record AtFaqRollbackMotive(AtFaqRollbackReason code, String detail) {

    public AtFaqRollbackMotive {
        if (code == null) {
            throw new IllegalArgumentException("A rollback reasonCode is mandatory (must not be null).");
        }
        detail = (detail == null || detail.isBlank()) ? null : detail.trim();
        if (code.requiresDetail() && detail == null) {
            throw new IllegalArgumentException(
                    "reasonCode " + code + " requires a non-blank reasonDetail.");
        }
    }

    /** Convenience factory for a code without complementary detail. */
    public static AtFaqRollbackMotive of(AtFaqRollbackReason code) {
        return new AtFaqRollbackMotive(code, null);
    }

    /** Convenience factory for a code with complementary detail. */
    public static AtFaqRollbackMotive of(AtFaqRollbackReason code, String detail) {
        return new AtFaqRollbackMotive(code, detail);
    }

    /** Whether a complementary detail is present after normalization. */
    public boolean hasDetail() {
        return detail != null;
    }

    /**
     * Stable governance string persisted in the audit log and carried through the rollback report.
     * Reuses the project's {@code key=value} audit-metadata convention.
     */
    public String auditDetail() {
        return hasDetail()
                ? "reasonCode=" + code.name() + "; reasonDetail=" + detail
                : "reasonCode=" + code.name();
    }
}
