package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import java.util.List;

/**
 * A single item's place in a governed publication <b>plan</b> (Bloco E — E7).
 *
 * <p>"Planear publicação não é publicar." This is a plan entry, not a publication and not a
 * persisted {@code KnowledgeQuestionAnswer}. It cross-references the E5 pre-curation proposal and
 * the E6 review decision, then records whether the item could proceed to a future real
 * publication step and why. It carries the proposed short/technical answer (from controlled
 * fixtures) but never raw HTML, prompts, chunks, logs or sensitive data.
 *
 * @param externalId              source id of the item
 * @param normalizedQuestion      whitespace-stable question
 * @param proposedPublicationPath the path E5 proposed
 * @param reviewDecisionType      the E6 decision type applied to the item
 * @param reviewedResultingPath   the path E6 produced after applying prudence rules
 * @param readiness               how far the item may proceed towards future publication
 * @param readyForFuturePublication whether every guard passed (never means published)
 * @param requiresAssistedReview  the item still needs assisted review
 * @param requiresManualReview    the item still needs mandatory manual review
 * @param blocked                 the item cannot proceed
 * @param deferred                the decision was deferred / missing / insufficient
 * @param proposedShortAnswer     deterministic short answer (from fixtures; may be null)
 * @param proposedTechnicalAnswer verbatim technical answer (from fixtures; null if absent)
 * @param proposedJurisdiction    proposed jurisdiction ("PT")
 * @param proposedLegalReferences detected/explicit legal references (may be empty)
 * @param sourceSummaries         short auditable summaries of the proposed sources
 * @param guardResult             the guard evaluation detail
 * @param nextActions             recommended follow-up for this candidate (never publication)
 */
public record AtFaqGovernedPublicationCandidate(
        String externalId,
        String normalizedQuestion,
        AtFaqBatchPublicationPath proposedPublicationPath,
        AtFaqReviewDecisionType reviewDecisionType,
        AtFaqBatchPublicationPath reviewedResultingPath,
        AtFaqGovernedPublicationReadiness readiness,
        boolean readyForFuturePublication,
        boolean requiresAssistedReview,
        boolean requiresManualReview,
        boolean blocked,
        boolean deferred,
        String proposedShortAnswer,
        String proposedTechnicalAnswer,
        KnowledgeTopic proposedTopic,
        String proposedSubtopic,
        String proposedJurisdiction,
        KnowledgeRiskLevel proposedRiskLevel,
        List<AtFaqPreCurationSourceCandidate> proposedSources,
        List<String> proposedLegalReferences,
        List<String> sourceSummaries,
        AtFaqGovernedPublicationGuardResult guardResult,
        List<String> nextActions) {

    public AtFaqGovernedPublicationCandidate {
        proposedSources = proposedSources == null ? List.of() : List.copyOf(proposedSources);
        proposedLegalReferences = proposedLegalReferences == null ? List.of() : List.copyOf(proposedLegalReferences);
        sourceSummaries = sourceSummaries == null ? List.of() : List.copyOf(sourceSummaries);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
    }
}
