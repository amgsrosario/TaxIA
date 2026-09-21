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
 * <p>E10B adds a small-batch gate on top of the same per-item contract: {@code batchMode} marks a
 * batch command, and {@code effectiveMaxItems} must be exactly 1 in single mode (E10A) or in
 * [2, {@value AtFaqRollbackTotals#MAX_SMALL_BATCH}] in batch mode (E10B) — one belongs to the single
 * flow, more than three is refused. "Calar um pequeno coro prova a governação", never mass silencing.
 *
 * @param externalId            stable AT-FAQ identifier of the candidate
 * @param knowledgeQaId         id of the published/indexed Q&amp;A to roll back, or {@code null}
 * @param reason                mandatory, non-blank motive for the rollback
 * @param unpublish             must be {@code true} for an eligible item (despublicar)
 * @param removeEmbedding       must be {@code true} (desindexar)
 * @param executeRagCheck       must be {@code true}; the before/after RAG state must be proven
 * @param productionDataAllowed always {@code false}; E10A/E10B only ever run against an isolated DB
 * @param guardChecks           human-readable guard checks that passed
 * @param warnings              non-blocking observations
 * @param blockingReasons       reasons rollback is refused (empty when the item is eligible)
 * @param requestedMaxItems     batch limit requested by the caller (1 for single mode)
 * @param effectiveMaxItems     batch limit actually applied (1 single; 2..3 batch)
 * @param batchMode             {@code true} for an E10B small-batch command, {@code false} for E10A single
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
        List<String> blockingReasons,
        int requestedMaxItems,
        int effectiveMaxItems,
        boolean batchMode) {

    public AtFaqRollbackCommand {
        guardChecks = guardChecks == null ? List.of() : List.copyOf(guardChecks);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
        boolean eligible = blockingReasons.isEmpty();
        if (eligible && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException(
                    "E10A/E10B requires a non-blank rollback reason for " + externalId);
        }
        if (eligible && !unpublish) {
            throw new IllegalArgumentException(
                    "An eligible rollback item must unpublish (despublicar): " + externalId);
        }
        if (eligible && !removeEmbedding) {
            throw new IllegalArgumentException(
                    "An eligible rollback item must remove its embedding (desindexar): " + externalId);
        }
        if (eligible && !executeRagCheck) {
            throw new IllegalArgumentException(
                    "Rollback must prove the RAG before/after state: executeRagCheck must be true for "
                            + externalId);
        }
        if (productionDataAllowed) {
            throw new IllegalArgumentException(
                    "Rollback never touches production data: productionDataAllowed must be false for "
                            + externalId);
        }
        if (eligible && !batchMode && effectiveMaxItems != 1) {
            throw new IllegalArgumentException(
                    "Single-mode rollback must have effectiveMaxItems == 1 for " + externalId);
        }
        if (eligible && batchMode
                && (effectiveMaxItems < 2 || effectiveMaxItems > AtFaqRollbackTotals.MAX_SMALL_BATCH)) {
            throw new IllegalArgumentException(
                    "Small-batch rollback must have effectiveMaxItems in [2, "
                            + AtFaqRollbackTotals.MAX_SMALL_BATCH + "] for " + externalId);
        }
    }
}
