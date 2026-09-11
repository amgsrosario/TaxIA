package com.knowledgeflow.ingestion.atfaq.batch;

import java.util.List;
import java.util.UUID;

/**
 * Inspectable decision to roll back one published/indexed Q&amp;A (Bloco E — E10A).
 *
 * <p>Frase-mestra: "Retirar voz não é apagar conhecimento. É neutralizar a recuperação preservando
 * rasto e motivo." The command is the governance gate between an E9A/E9B indexing outcome and an
 * actual {@code unpublish(...)} + embedding removal: a rollback is only ever performed when a
 * non-blank reason is supplied, the run is restricted to a single Q&amp;A, both neutralization
 * effects are requested (despublicar <b>and</b> desindexar), the RAG before/after check runs, and
 * no production data is in play.
 *
 * <p>The {@code reason} is mandatory here by contract. The current {@code unpublish(...)} does not
 * persist a motive (see the E10-prep inventory); E10A therefore carries the reason through the
 * command and the report, and documents the lack of formal persistence as a gap for
 * E10B/E10-policy.
 *
 * @param externalId            stable AT-FAQ identifier of the candidate
 * @param knowledgeQaId         id of the published/indexed Q&amp;A to roll back, or {@code null}
 * @param reason                mandatory, non-blank motive for the rollback
 * @param unpublish             must be {@code true} for an eligible item (despublicar)
 * @param removeEmbedding       must be {@code true} (desindexar)
 * @param executeRagCheck       must be {@code true}; the before/after RAG state must be proven
 * @param productionDataAllowed always {@code false}; E10A only ever runs against an isolated DB
 * @param guardChecks           human-readable guard checks that passed
 * @param warnings              non-blocking observations
 * @param blockingReasons       reasons rollback is refused (empty when the item is eligible)
 */
public record AtFaqRollbackCommand(
        String externalId,
        UUID knowledgeQaId,
        String reason,
        boolean unpublish,
        boolean removeEmbedding,
        boolean executeRagCheck,
        boolean productionDataAllowed,
        List<String> guardChecks,
        List<String> warnings,
        List<String> blockingReasons) {

    public AtFaqRollbackCommand {
        guardChecks = guardChecks == null ? List.of() : List.copyOf(guardChecks);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
        boolean eligible = blockingReasons.isEmpty();
        if (eligible && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException(
                    "E10A requires a non-blank rollback reason for " + externalId);
        }
        if (eligible && !unpublish) {
            throw new IllegalArgumentException(
                    "An eligible E10A item must unpublish (despublicar): " + externalId);
        }
        if (eligible && !removeEmbedding) {
            throw new IllegalArgumentException(
                    "An eligible E10A item must remove its embedding (desindexar): " + externalId);
        }
        if (eligible && !executeRagCheck) {
            throw new IllegalArgumentException(
                    "E10A must prove the RAG before/after state: executeRagCheck must be true for "
                            + externalId);
        }
        if (productionDataAllowed) {
            throw new IllegalArgumentException(
                    "E10A never touches production data: productionDataAllowed must be false for "
                            + externalId);
        }
    }
}
