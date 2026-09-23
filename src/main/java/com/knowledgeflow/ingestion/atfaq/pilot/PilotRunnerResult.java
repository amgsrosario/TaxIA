package com.knowledgeflow.ingestion.atfaq.pilot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The immutable outcome of one {@link TaxiaPilotGovernedRunner} invocation (Bloco E, E9C).
 *
 * <p>It carries the machine-readable {@link #outcome()}, whether a write actually happened
 * ({@link #wroteChange()}), the resolved target id when there was one, and a set of human-facing
 * detail lines. {@link #render()} produces the operator-facing text, always ending with the mandated
 * terminal line — {@code READINESS=...} for {@code status}, {@code RESULT=...} otherwise (PASSO 18).
 *
 * <p>Every possible result is representable here: there is no path where the runner surfaces a raw
 * exception as its normal interface.
 */
public record PilotRunnerResult(
        PilotRunnerAction action,
        PilotRunnerOutcome outcome,
        boolean wroteChange,
        UUID targetQaId,
        List<String> details) {

    public PilotRunnerResult {
        details = List.copyOf(details);
    }

    /** The mandated terminal line, e.g. {@code READINESS=READY} or {@code RESULT=PUBLISHED}. */
    public String terminalLine() {
        String key = action == PilotRunnerAction.STATUS ? "READINESS" : "RESULT";
        return key + "=" + outcome.name();
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

    // ---- builders (kept package-private; only the runner constructs results) ----------------

    static Builder of(PilotRunnerAction action) {
        return new Builder(action);
    }

    static final class Builder {
        private final PilotRunnerAction action;
        private final List<String> details = new ArrayList<>();
        private UUID targetQaId;

        private Builder(PilotRunnerAction action) {
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

        PilotRunnerResult build(PilotRunnerOutcome outcome, boolean wroteChange) {
            return new PilotRunnerResult(action, outcome, wroteChange, targetQaId, details);
        }
    }
}
