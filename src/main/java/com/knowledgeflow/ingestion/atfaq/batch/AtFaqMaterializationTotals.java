package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Aggregate counters for a governed AT-FAQ draft materialization (Bloco E — E8A).
 *
 * <p>Invariants: {@code published == 0} and {@code indexed == 0} — materializing a draft is not
 * publishing it. These two counters exist only to make that invariant explicit and assertable.
 *
 * @param totalCandidates    number of plan candidates seen
 * @param readyFromPlan      candidates the plan marked READY_FOR_FUTURE_PUBLICATION
 * @param materialized       candidates turned into a curable draft
 * @param persisted          drafts persisted (always 0 in the in-memory pass)
 * @param skipped            candidates skipped because they were not ready
 * @param blocked            ready candidates that still failed the assembler guards
 * @param withSources        materialized drafts carrying at least one source
 * @param withTechnicalAnswer materialized drafts carrying a technical answer
 * @param published          always 0 — E8A never publishes
 * @param indexed            always 0 — E8A never indexes
 */
public record AtFaqMaterializationTotals(
        int totalCandidates,
        int readyFromPlan,
        int materialized,
        int persisted,
        int skipped,
        int blocked,
        int withSources,
        int withTechnicalAnswer,
        int published,
        int indexed) {
}
