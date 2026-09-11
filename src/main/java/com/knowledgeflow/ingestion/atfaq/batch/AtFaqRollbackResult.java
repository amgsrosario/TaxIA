package com.knowledgeflow.ingestion.atfaq.batch;

import java.time.Instant;
import java.util.List;

/**
 * Full report of a governed AT-FAQ rollback run (Bloco E — E10A).
 *
 * <p>Frase-mestra: "Retirar voz não é apagar conhecimento. É neutralizar a recuperação preservando
 * rasto e motivo." This report records that at most one published/indexed Q&amp;A was rolled back in
 * an isolated database — unpublished through the real
 * {@code KnowledgeQuestionAnswerPublicationService#unpublish}, its embedding physically removed, and
 * proven to be no longer retrievable by the RAG — while the existing
 * {@code KNOWLEDGE_QA_UNPUBLISHED} audit trail is preserved and the mandatory reason is carried
 * through. Despublicar and desindexar are distinct-but-coordinated effects; neither deletes the
 * knowledge itself.
 *
 * <p>It carries no embeddings/vectors, no passages, no prompts, no chunks, no raw HTML and no
 * sensitive logs — only governance-relevant state.
 *
 * @param batchId        batch identifier carried from the indexing result
 * @param rolledBackAt   instant the rollback ran (from an injected clock)
 * @param rolledBackBy   actor that requested the rollback
 * @param mode           execution mode; the only mode is the single-Q&amp;A isolated test
 * @param totals         aggregate counters, capped at a single rolled-back Q&amp;A
 * @param itemResults    per-Q&amp;A rollback outcomes
 * @param globalWarnings run-level non-blocking observations
 * @param blockingErrors run-level blocking errors (empty on a clean run)
 * @param nextActions    recommended follow-ups (E10B batch rollback; E10-policy; then E9C)
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
        List<String> nextActions) {

    public AtFaqRollbackResult {
        itemResults = itemResults == null ? List.of() : List.copyOf(itemResults);
        globalWarnings = globalWarnings == null ? List.of() : List.copyOf(globalWarnings);
        blockingErrors = blockingErrors == null ? List.of() : List.copyOf(blockingErrors);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
    }
}
