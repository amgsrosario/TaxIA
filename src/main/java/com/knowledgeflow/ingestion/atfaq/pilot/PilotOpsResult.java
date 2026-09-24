package com.knowledgeflow.ingestion.atfaq.pilot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The immutable outcome of one {@link TaxiaPilotOperations} invocation — Bloco E,
 * E9C-pilot-operations (PROMPT 100).
 *
 * <p>It carries the machine-readable {@link #outcome()}, whether a write actually happened
 * ({@link #wroteChange()}), the resolved target id when there was one, and a set of human-facing
 * detail lines. {@link #render()} produces the operator-facing text, always ending with the mandated
 * terminal line {@code RESULT=<outcome>}.
 *
 * <p>Every possible result is representable here: there is no path where the tooling surfaces a raw
 * exception as its normal interface.
 */
public record PilotOpsResult(
        PilotOpsAction action,
        PilotOpsOutcome outcome,
        boolean wroteChange,
        UUID targetQaId,
        List<String> details) {

    public PilotOpsResult {
        details = List.copyOf(details);
    }

    /** The mandated terminal line, e.g. {@code RESULT=IMPORTED} or {@code RESULT=BLOCKED}. */
    public String terminalLine() {
        return "RESULT=" + outcome.name();
    }

    /** Full operator-facing text: one detail line per row, then the terminal line. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("action=").append(action.name()).append('\n');
        for (String line : details) {
            sb.append(line).append('\n');
        }
        sb.append(terminalLine());
        return sb.toString();
    }

    // ---- builders (kept package-private; only the operations bean constructs results) --------

    static Builder of(PilotOpsAction action) {
        return new Builder(action);
    }

    static final class Builder {
        private final PilotOpsAction action;
        private final List<String> details = new ArrayList<>();
        private UUID targetQaId;

        private Builder(PilotOpsAction action) {
            this.action = action;
        }

        Builder detail(String line) {
            this.details.add(line);
            return this;
        }

        Builder target(UUID id) {
            this.targetQaId = id;
            return this;
        }

        PilotOpsResult build(PilotOpsOutcome outcome, boolean wroteChange) {
            return new PilotOpsResult(action, outcome, wroteChange, targetQaId, details);
        }
    }
}
