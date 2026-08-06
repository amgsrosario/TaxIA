package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import java.util.List;

/**
 * Per-item outcome of the controlled AT-FAQ batch simulation (Bloco E — E4).
 *
 * <p>Audit-friendly and safe to log: it carries the normalized question, the content hash
 * and the proposed governance path plus human-readable reasons/warnings. It deliberately
 * does NOT carry the full raw answer, raw HTML, chunks or prompts.
 *
 * @param externalId        source id of the item
 * @param sourceUrl         source URL as a plain string (never fetched)
 * @param normalizedQuestion whitespace-stable question used for duplicate/conflict detection
 * @param contentHash       deterministic hash of the normalized question+answer
 * @param proposedPath      proposed publication path (proposal, not an action)
 * @param reasons           why this path was chosen
 * @param warnings          non-blocking notes a curator should see
 * @param duplicateCandidate whether the item collides with another item in the batch
 * @param conflictCandidate whether the item conflicts with another item in the batch
 * @param importedRaw       whether a usable RAW form was produced
 * @param preCurated        whether the item reached the pre-curation proposal stage
 * @param proposedTopic     proposed topic (optional, may be null)
 * @param proposedRiskLevel proposed risk level
 * @param hasTechnicalAnswer whether a non-blank technical answer is present
 * @param hasLegalReference whether a non-blank legal reference is present
 * @param officialSource    whether the source is official
 */
public record AtFaqBatchItemSummary(
        String externalId,
        String sourceUrl,
        String normalizedQuestion,
        String contentHash,
        AtFaqBatchPublicationPath proposedPath,
        List<String> reasons,
        List<String> warnings,
        boolean duplicateCandidate,
        boolean conflictCandidate,
        boolean importedRaw,
        boolean preCurated,
        String proposedTopic,
        KnowledgeRiskLevel proposedRiskLevel,
        boolean hasTechnicalAnswer,
        boolean hasLegalReference,
        boolean officialSource) {

    public AtFaqBatchItemSummary {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
