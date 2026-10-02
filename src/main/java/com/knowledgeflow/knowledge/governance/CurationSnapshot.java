package com.knowledgeflow.knowledge.governance;

import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import java.time.LocalDate;

/** Valores curados de uma Q&amp;A, para comparar o estado actual com o pedido. */
public record CurationSnapshot(
        String normalizedQuestion,
        String shortAnswer,
        String technicalAnswer,
        KnowledgeTopic topic,
        String subtopic,
        String jurisdiction,
        KnowledgeRiskLevel riskLevel,
        boolean requiresHumanValidation,
        LocalDate validFrom,
        LocalDate validTo,
        String notes
) {}
