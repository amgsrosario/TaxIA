package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.ai.documented.FreshnessStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import java.util.List;

/**
 * A structured pre-curation <b>proposal</b> for a single AT-FAQ item (Bloco E — E5).
 *
 * <p>This is a proposal, not a publication and not a persisted {@code KnowledgeQuestionAnswer}.
 * Nothing here is authoritative: it prepares a case for human/governed review. The proposed
 * technical answer is never invented — when the source lacks one, it is left null and a warning
 * is raised. Safe to log: no raw HTML, no chunks, no prompts.
 *
 * @param externalId                    source id of the item
 * @param sourceUrl                     source URL as a plain string (never fetched)
 * @param normalizedQuestion            whitespace-stable question
 * @param proposedShortAnswer           deterministic short summary of the existing answer (null if none)
 * @param proposedTechnicalAnswer       existing technical answer, verbatim; null if absent (never invented)
 * @param proposedTopic                 proposed fiscal topic (falls back to {@link KnowledgeTopic#OUTROS})
 * @param proposedSubtopic              proposed subtopic; null when no explicit marker exists
 * @param proposedJurisdiction          proposed jurisdiction ("PT" for AT FAQ)
 * @param proposedRiskLevel             proposed risk level (never lowered by heuristic)
 * @param proposedSources               proposed source candidates (FAQ and/or legislation)
 * @param proposedLegalReferences       detected/explicit legal references (may be empty)
 * @param proposedFreshnessStatus       proposed actuality (UNCERTAIN unless explicit marker)
 * @param proposedPublicationPath       inherited E4 path, escalated for prudence (never relaxed)
 * @param duplicateCandidates           external ids of other items sharing this content
 * @param conflictCandidates            markers/ids indicating a conflict
 * @param confidenceSignals             simple textual signals a reviewer can weigh
 * @param warnings                      non-blocking notes
 * @param requiredReviewReason          main reason review is required (null when AUTO_CONTROLLED)
 * @param eligibleForAutoControlledCandidate whether the proposal is an auto-controlled candidate
 */
public record AtFaqPreCuratedBatchItem(
        String externalId,
        String sourceUrl,
        String normalizedQuestion,
        String proposedShortAnswer,
        String proposedTechnicalAnswer,
        KnowledgeTopic proposedTopic,
        String proposedSubtopic,
        String proposedJurisdiction,
        KnowledgeRiskLevel proposedRiskLevel,
        List<AtFaqPreCurationSourceCandidate> proposedSources,
        List<String> proposedLegalReferences,
        FreshnessStatus proposedFreshnessStatus,
        AtFaqBatchPublicationPath proposedPublicationPath,
        List<String> duplicateCandidates,
        List<String> conflictCandidates,
        List<String> confidenceSignals,
        List<String> warnings,
        String requiredReviewReason,
        boolean eligibleForAutoControlledCandidate) {

    public AtFaqPreCuratedBatchItem {
        proposedSources = proposedSources == null ? List.of() : List.copyOf(proposedSources);
        proposedLegalReferences = proposedLegalReferences == null ? List.of() : List.copyOf(proposedLegalReferences);
        duplicateCandidates = duplicateCandidates == null ? List.of() : List.copyOf(duplicateCandidates);
        conflictCandidates = conflictCandidates == null ? List.of() : List.copyOf(conflictCandidates);
        confidenceSignals = confidenceSignals == null ? List.of() : List.copyOf(confidenceSignals);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
