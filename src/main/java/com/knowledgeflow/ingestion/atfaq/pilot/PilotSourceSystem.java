package com.knowledgeflow.ingestion.atfaq.pilot;

/**
 * Fail-closed validation of an explicit pilot <b>source system</b> token — Bloco E,
 * E9C-pilot-operations (PROMPT 100).
 *
 * <p>Extracted so the guarded one-shot governed runner ({@link TaxiaPilotGovernedRunner}) and the
 * guarded one-shot operations tooling ({@link TaxiaPilotOperations}) share a single, identical
 * definition of "a single explicit source system" — never two subtly different notions of what a
 * valid namespace is. Both resolve an N=1 target by an explicit {@code (sourceSystem, externalKey)}
 * pair, so both must reject the same malformed source systems in exactly the same way.
 *
 * <p>A valid source system is mandatory, trimmed, non-blank, and exactly one plain token: no
 * wildcards ({@code * % ?}) and no multi-value separators ({@code , ; |} or internal whitespace).
 * There is deliberately no closed global taxonomy — any explicit token such as {@code at-faq} or
 * {@code taxia-curated} is accepted. The runner never infers or defaults it.
 */
public final class PilotSourceSystem {

    private PilotSourceSystem() {
    }

    /**
     * Validates an explicit source system. Returns {@code null} when valid, else the exact refusal
     * message the callers surface to the operator.
     */
    public static String validate(String sourceSystem) {
        if (sourceSystem == null || sourceSystem.isBlank()) {
            return "sourceSystem is required";
        }
        String system = sourceSystem.trim();
        if (system.indexOf('*') >= 0 || system.indexOf('%') >= 0 || system.indexOf('?') >= 0) {
            return "sourceSystem must be a single explicit value without wildcards ('" + system + "')";
        }
        if (system.indexOf(',') >= 0 || system.indexOf(';') >= 0 || system.indexOf('|') >= 0
                || system.matches(".*\\s.*")) {
            return "sourceSystem must be exactly one value without separators ('" + system + "')";
        }
        return null;
    }
}
