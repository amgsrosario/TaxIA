package com.knowledgeflow.ingestion.atfaq.batch;

import java.time.Instant;
import java.util.List;

/**
 * Full report of a governed AT-FAQ draft persistence run (Bloco E — E8B.2).
 *
 * <p>"Persistir draft não é criar trabalho humano. É preparar conhecimento para publicação
 * governada, automática quando segura." This report records which curable drafts survived into the
 * (isolated) database as {@code IMPORTED} knowledge, and their future-autonomy classification —
 * while its totals assert that nothing was published, indexed or embedded. It carries no
 * embeddings, no raw HTML, no prompts and no chunks.
 *
 * @param batchId        batch identifier carried from the materialization result
 * @param persistedAt    instant the persistence ran (from an injected clock)
 * @param persistedBy    actor that requested the persistence
 * @param mode           persistence mode; no mode publishes or indexes
 * @param totals         aggregate counters, with hard-zero publication/index/embedding invariants
 * @param itemResults    per-draft persistence outcomes
 * @param globalWarnings batch-level non-blocking observations
 * @param blockingErrors batch-level blocking errors (empty on a clean run)
 * @param nextActions    recommended follow-ups toward governed publication (E8B.3)
 */
public record AtFaqDraftPersistenceResult(
        String batchId,
        Instant persistedAt,
        String persistedBy,
        AtFaqDraftPersistenceMode mode,
        AtFaqDraftPersistenceTotals totals,
        List<AtFaqDraftPersistenceItemResult> itemResults,
        List<String> globalWarnings,
        List<String> blockingErrors,
        List<String> nextActions) {

    public AtFaqDraftPersistenceResult {
        itemResults = itemResults == null ? List.of() : List.copyOf(itemResults);
        globalWarnings = globalWarnings == null ? List.of() : List.copyOf(globalWarnings);
        blockingErrors = blockingErrors == null ? List.of() : List.copyOf(blockingErrors);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
    }
}
