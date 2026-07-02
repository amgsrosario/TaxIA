package com.knowledgeflow.knowledge.dto;

import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import java.time.LocalDate;

/** Payload for PATCH /api/v1/admin/knowledge/qa/{id}/curation */
public record KnowledgeQaCurationRequest(
        String normalizedQuestion,
        String shortAnswer,
        String technicalAnswer,
        KnowledgeTopic topic,
        String subtopic,
        String jurisdiction,
        KnowledgeRiskLevel riskLevel,
        Boolean requiresHumanValidation,
        LocalDate validFrom,
        LocalDate validTo,
        String notes
) {
}
