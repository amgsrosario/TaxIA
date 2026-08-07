package com.knowledgeflow.ingestion.atfaq.batch;

import java.util.List;

/**
 * The outcome of applying the future-publication guards to one AT-FAQ candidate (Bloco E — E7).
 *
 * <p>All guards are textual and auditable — safe to log. They carry no sensitive data, no raw
 * HTML, no prompts and no chunks. A candidate is only ready for a future governed publication
 * when {@code passed} is true (i.e. {@code failedGuards} and {@code blockingReasons} are empty).
 *
 * @param passed         whether every guard passed
 * @param passedGuards   names of the guards that passed
 * @param failedGuards   names of the guards that failed
 * @param warnings       non-blocking notes raised while evaluating guards
 * @param blockingReasons blocking reasons that forbid future publication
 */
public record AtFaqGovernedPublicationGuardResult(
        boolean passed,
        List<String> passedGuards,
        List<String> failedGuards,
        List<String> warnings,
        List<String> blockingReasons) {

    public AtFaqGovernedPublicationGuardResult {
        passedGuards = passedGuards == null ? List.of() : List.copyOf(passedGuards);
        failedGuards = failedGuards == null ? List.of() : List.copyOf(failedGuards);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
    }
}
