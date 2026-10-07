package com.knowledgeflow.users.credentials;

import java.util.ArrayList;
import java.util.List;

/** Outcome of a break-glass recovery. Details never contain a password, hash, token or secret. */
public record BreakGlassResult(Outcome outcome, List<String> details) {

    public enum Outcome { RECOVERED, BLOCKED }

    static BreakGlassResult blocked(String reason) {
        return new BreakGlassResult(Outcome.BLOCKED, List.of(reason));
    }

    static BreakGlassResult recovered(List<String> details) {
        return new BreakGlassResult(Outcome.RECOVERED, List.copyOf(details));
    }

    public String render() {
        List<String> lines = new ArrayList<>();
        lines.add("ACTION=admin-recover");
        details.forEach(d -> lines.add("detail: " + d));
        lines.add("RESULT=" + outcome);
        return String.join(System.lineSeparator(), lines);
    }
}
