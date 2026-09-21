package com.knowledgeflow.ingestion.atfaq.batch;

import java.time.Instant;
import java.util.List;

/**
 * Full report of a governed AT-FAQ rollback run (Bloco E — E10A/E10B).
 *
 * <p>E10A frase-mestra: "Retirar voz não é apagar conhecimento. É neutralizar a recuperação
 * preservando rasto e motivo." E10B frase-mestra: "Calar uma voz prova o travão. Calar um pequeno
 * coro prova a governação." This report records that one Q&amp;A (E10A) or a small governed batch of
 * up to {@link AtFaqRollbackTotals#MAX_SMALL_BATCH} Q&amp;A (E10B) was rolled back in an isolated
 * database — each unpublished through the real
 * {@code KnowledgeQuestionAnswerPublicationService#unpublish}, its embedding physically removed, and
 * proven to be no longer retrievable by the RAG — while the existing
 * {@code KNOWLEDGE_QA_UNPUBLISHED} audit trail is preserved and the mandatory reason is carried
 * through. Despublicar and desindexar are distinct-but-coordinated effects; neither deletes the
 * knowledge itself.
 *
 * <p>It carries no embeddings/vectors, no passages, no prompts, no chunks, no raw HTML and no
 * sensitive logs — only governance-relevant state.
 *
 * @param batchId           batch identifier carried from the indexing result
 * @param rolledBackAt      instant the rollback ran (from an injected clock)
 * @param rolledBackBy      actor that requested the rollback
 * @param mode              execution mode (single-Q&amp;A or small-batch isolated test)
 * @param totals            aggregate counters, capped at the effective batch limit
 * @param itemResults       per-Q&amp;A rollback outcomes
 * @param globalWarnings    run-level non-blocking observations
 * @param blockingErrors    run-level blocking errors (empty on a clean run)
 * @param nextActions       recommended follow-ups (E10-policy; then E9C)
 * @param requestedMaxItems batch limit requested by the caller (1 for single mode), or {@code null}
 * @param effectiveMaxItems batch limit actually applied after clamping, or {@code null}
 */
public record AtFaqRollbackResult(
        String batchId,
        Instant rolledBackAt,
        String rolledBackBy,
        AtFaqRollbackMode mode,
        AtFaqRollbackTotals totals,
        List<AtFaqRollbackItemResult> itemResults,
        List<String> globalWarnings,
        List<String> blockingErrors,
        List<String> nextActions,
        Integer requestedMaxItems,
        Integer effectiveMaxItems) {

    public AtFaqRollbackResult {
        itemResults = itemResults == null ? List.of() : List.copyOf(itemResults);
        globalWarnings = globalWarnings == null ? List.of() : List.copyOf(globalWarnings);
        blockingErrors = blockingErrors == null ? List.of() : List.copyOf(blockingErrors);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
    }
}
