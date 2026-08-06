package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Aggregate counters for a controlled AT-FAQ batch report (Bloco E — E4).
 *
 * <p>In E4 the simulation never publishes and never indexes, so {@link #published} and
 * {@link #indexed} MUST always be {@code 0}. They exist so the report shape is already
 * aligned with the future governed-publication stage (E7/E8) without E4 exercising it.
 *
 * @param discovered               items received by the simulation
 * @param importedRaw              items with a usable RAW form (question + answer present)
 * @param preCurated               items that reached the pre-curation proposal stage
 * @param duplicates               items flagged as duplicate candidates
 * @param conflicts                items flagged as conflict candidates
 * @param rejected                 items rejected from publication (equal to {@link #notPublishable} in E4)
 * @param autoControlledCandidates items proposed as {@link AtFaqBatchPublicationPath#AUTO_CONTROLLED}
 * @param assistedCandidates       items proposed as {@link AtFaqBatchPublicationPath#ASSISTED}
 * @param manualRequiredCandidates items proposed as {@link AtFaqBatchPublicationPath#MANUAL_REQUIRED}
 * @param notPublishable           items proposed as {@link AtFaqBatchPublicationPath#NOT_PUBLISHABLE}
 * @param published                MUST be 0 in E4 — nothing is published
 * @param indexed                  MUST be 0 in E4 — nothing is indexed / no embeddings
 * @param failed                   items that could not be turned into a RAW form (structurally invalid)
 */
public record AtFaqBatchReportTotals(
        int discovered,
        int importedRaw,
        int preCurated,
        int duplicates,
        int conflicts,
        int rejected,
        int autoControlledCandidates,
        int assistedCandidates,
        int manualRequiredCandidates,
        int notPublishable,
        int published,
        int indexed,
        int failed) {
}
