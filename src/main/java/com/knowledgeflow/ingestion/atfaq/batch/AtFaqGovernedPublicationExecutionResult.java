package com.knowledgeflow.ingestion.atfaq.batch;

import java.time.Instant;
import java.util.List;

/**
 * Full report of a governed AT-FAQ publication run (Bloco E — E8B.3).
 *
 * <p>Named with the {@code Execution} infix to distinguish the E8B.3 execution artifacts from the
 * E7 publication-plan family that shares the {@code AtFaqGovernedPublication} prefix.
 *
 * <p>Frase-mestra: "Publicar em teste não é dar voz ao conhecimento. É provar que a promoção
 * governada até {@code publishedAt}/{@code publishedBy} respeita todos os guardas." This report
 * records which persisted drafts were promoted IMPORTED → VALIDATED and published via the real
 * {@code KnowledgeQuestionAnswerPublicationService}, in an isolated database under the stub
 * indexer, while its totals assert that nothing was indexed, embedded or made RAG-retrievable.
 *
 * <p>It carries no embeddings, no chunks, no prompts, no raw HTML and no sensitive logs — only
 * governance-relevant state.
 *
 * @param batchId        batch identifier carried from the persistence result
 * @param executedAt     instant the publication ran (from an injected clock)
 * @param executedBy     actor that requested the publication
 * @param mode           execution mode; the only mode is the stub-indexer isolated test
 * @param totals         aggregate counters, with hard-zero index/embedding/RAG invariants
 * @param itemResults    per-draft publication outcomes
 * @param globalWarnings batch-level non-blocking observations
 * @param blockingErrors batch-level blocking errors (empty on a clean run)
 * @param nextActions    recommended follow-ups toward real indexation/RAG (E9)
 */
public record AtFaqGovernedPublicationExecutionResult(
        String batchId,
        Instant executedAt,
        String executedBy,
        AtFaqGovernedPublicationExecutionMode mode,
        AtFaqGovernedPublicationExecutionTotals totals,
        List<AtFaqGovernedPublicationExecutionItemResult> itemResults,
        List<String> globalWarnings,
        List<String> blockingErrors,
        List<String> nextActions) {

    public AtFaqGovernedPublicationExecutionResult {
        itemResults = itemResults == null ? List.of() : List.copyOf(itemResults);
        globalWarnings = globalWarnings == null ? List.of() : List.copyOf(globalWarnings);
        blockingErrors = blockingErrors == null ? List.of() : List.copyOf(blockingErrors);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
    }
}
