package com.knowledgeflow.ingestion.atfaq.batch;

import java.time.Instant;
import java.util.List;

/**
 * Full report of a governed AT-FAQ publication dry-run (Bloco E — E8B.1).
 *
 * <p>"Ensaiar publicação não é publicar. É validar que o executor respeita os guardas antes de
 * tocar na BD." This report is the sole output of the dry-run: it describes what a future real
 * publication would attempt, item by item, while asserting through its totals that nothing was
 * persisted, published or indexed. It is deterministic — same input and clock yield an equal report.
 *
 * @param batchId        batch identifier carried from the E8A materialization result
 * @param executedAt     instant the dry-run ran (from an injected clock)
 * @param executedBy     actor that requested the dry-run
 * @param mode           execution mode; every mode implies zero real effects
 * @param totals         aggregate counters, with hard-zero real-effect invariants
 * @param itemResults    per-draft rehearsal outcomes
 * @param globalWarnings batch-level non-blocking observations
 * @param blockingErrors batch-level blocking errors (empty on a clean rehearsal)
 * @param nextActions    recommended follow-ups toward real governed publication (E8B.2)
 */
public record AtFaqPublicationDryRunReport(
        String batchId,
        Instant executedAt,
        String executedBy,
        AtFaqPublicationDryRunMode mode,
        AtFaqPublicationDryRunTotals totals,
        List<AtFaqPublicationDryRunItemResult> itemResults,
        List<String> globalWarnings,
        List<String> blockingErrors,
        List<String> nextActions) {

    public AtFaqPublicationDryRunReport {
        itemResults = itemResults == null ? List.of() : List.copyOf(itemResults);
        globalWarnings = globalWarnings == null ? List.of() : List.copyOf(globalWarnings);
        blockingErrors = blockingErrors == null ? List.of() : List.copyOf(blockingErrors);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
    }
}
