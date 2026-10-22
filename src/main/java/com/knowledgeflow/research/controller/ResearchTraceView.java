package com.knowledgeflow.research.controller;

import com.knowledgeflow.research.entity.CriticalMemoryEntry;
import com.knowledgeflow.research.entity.KnowledgeProposition;
import com.knowledgeflow.research.entity.KnowledgePropositionRelation;
import com.knowledgeflow.research.entity.ResearchEvidence;
import com.knowledgeflow.research.entity.ResearchInvestigation;
import com.knowledgeflow.research.entity.ResearchStep;
import com.knowledgeflow.research.service.InvestigationTraceService.Trace;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Vista de rastreabilidade de uma investigação: pergunta → passos → evidência → conhecimento → resposta. */
public record ResearchTraceView(
        UUID id,
        String question,
        String trigger,
        String status,
        String answerType,
        String parecerRequirement,
        String answer,
        List<String> decisionReasons,
        List<String> limitations,
        List<String> assumptions,
        String failureReason,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt,
        List<StepView> steps,
        List<EvidenceView> evidence,
        List<PropositionView> propositions,
        List<RelationView> relations,
        List<CriticalMemoryView> criticalMemory) {

    public record StepView(int order, String phase, String origin, String target, String url, Integer httpStatus,
            String outcome, String detail, OffsetDateTime occurredAt) {}

    public record EvidenceView(UUID id, String sourceType, String authority, String referenceKey,
            String legalReference, String title, String url, OffsetDateTime retrievedAt, String contentSha256,
            String content, List<String> amendmentNotes, String validityStatus, String validityReason,
            String classification, String classificationReason, String promotionStatus, UUID consultationStepId) {}

    public record SupportView(UUID evidenceId, String anchorText, boolean anchored) {}

    public record ConditionView(String kind, String description, String anchorText, boolean anchored) {}

    public record PropositionView(UUID id, String kind, String statement, String foundation, String scope,
            String applicability, String validityStatus, String confidence, String status, String statusReason,
            List<SupportView> supports, List<ConditionView> conditions) {}

    public record RelationView(UUID from, UUID to, String relation, boolean resolved, String note) {}

    public record CriticalMemoryView(UUID id, String classification, String claim, String reason, String provenance,
            String referenceKey, String sourceUrl, String sourceVersion, UUID evidenceId, UUID propositionId,
            UUID contradictedByEvidenceId, UUID contradictedByPropositionId, String status, int occurrences,
            OffsetDateTime observedAt, OffsetDateTime updatedAt) {

        public static CriticalMemoryView from(CriticalMemoryEntry e) {
            return new CriticalMemoryView(e.getId(), e.getClassification().name(), e.getClaim(), e.getReason(),
                    e.getProvenance(), e.getReferenceKey(), e.getSourceUrl(), e.getSourceVersion(), e.getEvidenceId(),
                    e.getPropositionId(), e.getContradictedByEvidenceId(), e.getContradictedByPropositionId(),
                    e.getStatus().name(), e.getOccurrences(), e.getObservedAt(), e.getUpdatedAt());
        }
    }

    public static ResearchTraceView from(Trace t) {
        ResearchInvestigation i = t.investigation();
        return new ResearchTraceView(i.getId(), i.getQuestion(), i.getTriggerReason().name(), i.getStatus().name(),
                i.getAnswerType() == null ? null : i.getAnswerType().name(),
                i.getParecerRequirement() == null ? null : i.getParecerRequirement().name(),
                i.getAnswerText(), lines(i.getDecisionReasons()), lines(i.getLimitations()), lines(i.getAssumptions()),
                i.getFailureReason(), i.getCreatedAt(), i.getCompletedAt(),
                t.steps().stream().map(ResearchTraceView::step).toList(),
                t.evidence().stream().map(ResearchTraceView::evidence).toList(),
                t.propositions().stream().map(ResearchTraceView::proposition).toList(),
                t.relations().stream().map(r -> new RelationView(r.getFromPropositionId(), r.getToPropositionId(),
                        r.getRelation().name(), r.isResolved(), r.getNote())).toList(),
                t.criticalMemory().stream().map(CriticalMemoryView::from).toList());
    }

    private static StepView step(ResearchStep s) {
        return new StepView(s.getStepOrder(), s.getPhase().name(), s.getOrigin().name(), s.getTarget(), s.getUrl(),
                s.getHttpStatus(), s.getOutcome().name(), s.getDetail(), s.getOccurredAt());
    }

    private static EvidenceView evidence(ResearchEvidence e) {
        return new EvidenceView(e.getId(), e.getSourceType().name(), e.getAuthority(), e.getReferenceKey(),
                e.getLegalReference(), e.getTitle(), e.getUrl(), e.getRetrievedAt(), e.getContentSha256(),
                e.getContent(), lines(e.getAmendmentNotes()), e.getValidityStatus().name(), e.getValidityReason(),
                e.getClassification().name(), e.getClassificationReason(), e.getPromotionStatus(),
                e.getConsultationStepId());
    }

    private static PropositionView proposition(KnowledgeProposition p) {
        return new PropositionView(p.getId(), p.getKind().name(), p.getStatement(), p.getFoundation(), p.getScope(),
                p.getApplicability(), p.getValidityStatus().name(), p.getConfidence().name(), p.getStatus().name(),
                p.getStatusReason(),
                p.getSupports().stream().map(s -> new SupportView(s.getEvidenceId(), s.getAnchorText(), s.isAnchored())).toList(),
                p.getConditions().stream().map(c -> new ConditionView(c.getKind().name(), c.getDescription(),
                        c.getAnchorText(), c.isAnchored())).toList());
    }

    private static List<String> lines(String text) {
        return text == null || text.isBlank() ? List.of() : Arrays.stream(text.split("\n")).filter(l -> !l.isBlank()).toList();
    }
}
