package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.ai.documented.FreshnessStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;

/**
 * Input item for the controlled AT-FAQ batch simulation (Bloco E — E4).
 *
 * <p>Represents a single candidate Q&amp;A as it would arrive from a local fixture — never
 * from live scraping or an external call. It carries only the fields the simulation needs
 * to normalize, detect duplicates/conflicts and propose a publication path. It holds no
 * secrets, no raw HTML page and no prompts.
 *
 * <p>Markers ({@code conflictMarker}, {@code manualMarker}) let a fixture assert an intended
 * governance outcome explicitly, independently of heuristic detection.
 *
 * @param externalId     stable id of the FAQ item at the (fictitious/local) source
 * @param sourceUrl      source URL as a plain string — never fetched in E4 (may be fictitious)
 * @param sourceTitle    human-readable source label (optional)
 * @param question       original question text
 * @param answer         original answer text
 * @param technicalAnswer proposed curated technical answer (may be blank/null when absent)
 * @param topic          proposed topic (e.g. "IVA"); optional
 * @param riskLevel      proposed risk level; {@code null} treated as {@link KnowledgeRiskLevel#MEDIUM}
 * @param legalReference proposed legal reference/foundation (blank/null when absent)
 * @param officialSource whether the source is an official AT/legal source
 * @param freshnessStatus actuality of the supporting source; {@code null} treated as {@link FreshnessStatus#UNCERTAIN}
 * @param conflictMarker explicit fixture marker: this item is known to conflict with existing knowledge
 * @param manualMarker   explicit fixture marker: this item must go through manual decision regardless of heuristics
 */
public record AtFaqControlledBatchItem(
        String externalId,
        String sourceUrl,
        String sourceTitle,
        String question,
        String answer,
        String technicalAnswer,
        String topic,
        KnowledgeRiskLevel riskLevel,
        String legalReference,
        boolean officialSource,
        FreshnessStatus freshnessStatus,
        boolean conflictMarker,
        boolean manualMarker) {

    /** Never {@code null}: defaults to {@link KnowledgeRiskLevel#MEDIUM}. */
    public KnowledgeRiskLevel effectiveRiskLevel() {
        return riskLevel != null ? riskLevel : KnowledgeRiskLevel.MEDIUM;
    }

    /** Never {@code null}: defaults to {@link FreshnessStatus#UNCERTAIN}. */
    public FreshnessStatus effectiveFreshness() {
        return freshnessStatus != null ? freshnessStatus : FreshnessStatus.UNCERTAIN;
    }
}
