package com.knowledgeflow.knowledge.dto;

import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Summary response for list endpoints. */
public record KnowledgeQaResponse(
        UUID id,
        String externalKey,
        String sourceSystem,
        String originalQuestion,
        KnowledgeTopic topic,
        String subtopic,
        KnowledgeRiskLevel riskLevel,
        KnowledgeCurationStatus curationStatus,
        boolean canonical,
        boolean requiresHumanValidation,
        LocalDate validFrom,
        LocalDate validTo,
        boolean published,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static KnowledgeQaResponse from(KnowledgeQuestionAnswer qa) {
        return new KnowledgeQaResponse(
                qa.getId(),
                qa.getExternalKey(),
                qa.getSourceSystem(),
                qa.getOriginalQuestion(),
                qa.getTopic(),
                qa.getSubtopic(),
                qa.getRiskLevel(),
                qa.getCurationStatus(),
                qa.isCanonical(),
                qa.isRequiresHumanValidation(),
                qa.getValidFrom(),
                qa.getValidTo(),
                qa.isPublished(),
                qa.getCreatedAt(),
                qa.getUpdatedAt());
    }
}
