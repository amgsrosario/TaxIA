package com.knowledgeflow.knowledge.dto;

import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Full detail response including original content, curated fields, and sources. */
public record KnowledgeQaDetailResponse(
        UUID id,
        String externalKey,
        String sourceSystem,
        String originalQuestion,
        String originalAnswer,
        String normalizedQuestion,
        String shortAnswer,
        String technicalAnswer,
        KnowledgeTopic topic,
        String subtopic,
        String jurisdiction,
        KnowledgeRiskLevel riskLevel,
        boolean requiresHumanValidation,
        KnowledgeCurationStatus curationStatus,
        boolean canonical,
        LocalDate validFrom,
        LocalDate validTo,
        String reviewedBy,
        OffsetDateTime reviewedAt,
        String notes,
        boolean published,
        OffsetDateTime publishedAt,
        String publishedBy,
        UUID previousVersionId,
        int version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<SourceReferenceResponse> sources
) {
    public static KnowledgeQaDetailResponse from(
            KnowledgeQuestionAnswer qa, List<KnowledgeSourceReference> sources) {
        return new KnowledgeQaDetailResponse(
                qa.getId(),
                qa.getExternalKey(),
                qa.getSourceSystem(),
                qa.getOriginalQuestion(),
                qa.getOriginalAnswer(),
                qa.getNormalizedQuestion(),
                qa.getShortAnswer(),
                qa.getTechnicalAnswer(),
                qa.getTopic(),
                qa.getSubtopic(),
                qa.getJurisdiction(),
                qa.getRiskLevel(),
                qa.isRequiresHumanValidation(),
                qa.getCurationStatus(),
                qa.isCanonical(),
                qa.getValidFrom(),
                qa.getValidTo(),
                qa.getReviewedBy(),
                qa.getReviewedAt(),
                qa.getNotes(),
                qa.isPublished(),
                qa.getPublishedAt(),
                qa.getPublishedBy(),
                qa.getPreviousVersionId(),
                qa.getVersion(),
                qa.getCreatedAt(),
                qa.getUpdatedAt(),
                sources.stream().map(SourceReferenceResponse::from).toList());
    }
}
