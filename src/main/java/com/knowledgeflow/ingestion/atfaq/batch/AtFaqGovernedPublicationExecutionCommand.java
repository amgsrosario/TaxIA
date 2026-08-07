package com.knowledgeflow.ingestion.atfaq.batch;

import java.util.List;
import java.util.UUID;

/**
 * A single, resolved decision to (attempt to) publish one persisted AT-FAQ draft (Bloco E — E8B.3).
 *
 * <p>Named with the {@code Execution} infix to distinguish the E8B.3 <em>execution</em> artifacts
 * from the E7 publication-<em>plan</em> family ({@code AtFaqGovernedPublicationPlan},
 * {@code AtFaqGovernedPublicationTotals}, …), which share the {@code AtFaqGovernedPublication}
 * prefix.
 *
 * <p>The executor first builds one command per persisted draft, then acts on it. A command is a
 * pure, inspectable record of "what we intend to do and why it is safe" — it holds no embeddings,
 * no raw HTML and no prompts. Its invariants encode the governance guarantees of E8B.3:
 *
 * <ul>
 *   <li>{@code publish == true} only when the draft is future-autonomy eligible and every guard
 *       passed (LOW risk, official source, legal reference, technical answer, no human gate,
 *       no blocking reason);</li>
 *   <li>{@code indexExpected == false} always — this step never expects a real embedding;</li>
 *   <li>{@code requiresStubIndexer == true} always — publication is only ever attempted where the
 *       no-op stub indexer is active.</li>
 * </ul>
 *
 * @param externalId          stable AT-FAQ identifier
 * @param knowledgeQaId       id of the persisted Q&amp;A this command targets (may be {@code null}
 *                            when the draft was never persisted)
 * @param normalizedQuestion  normalized question text (for traceability only)
 * @param validateBeforePublish whether the draft must be promoted IMPORTED → VALIDATED first
 * @param publish             whether real publication should be attempted
 * @param indexExpected       always {@code false}
 * @param requiresStubIndexer always {@code true}
 * @param guardChecks         human-readable list of the guards that were evaluated
 * @param warnings            non-blocking observations
 * @param blockingReasons     reasons publication must not be attempted (empty when {@code publish})
 */
public record AtFaqGovernedPublicationExecutionCommand(
        String externalId,
        UUID knowledgeQaId,
        String normalizedQuestion,
        boolean validateBeforePublish,
        boolean publish,
        boolean indexExpected,
        boolean requiresStubIndexer,
        List<String> guardChecks,
        List<String> warnings,
        List<String> blockingReasons) {

    public AtFaqGovernedPublicationExecutionCommand {
        guardChecks = guardChecks == null ? List.of() : List.copyOf(guardChecks);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
        if (publish && !blockingReasons.isEmpty()) {
            throw new IllegalArgumentException(
                    "A publish command must carry no blocking reasons: " + externalId);
        }
        if (indexExpected) {
            throw new IllegalArgumentException(
                    "E8B.3 never expects real indexing: " + externalId);
        }
        if (!requiresStubIndexer) {
            throw new IllegalArgumentException(
                    "E8B.3 publication is only permitted with the stub indexer active: " + externalId);
        }
    }
}
