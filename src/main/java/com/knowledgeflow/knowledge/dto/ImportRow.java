package com.knowledgeflow.knowledge.dto;

/** A single row parsed from a CSV or JSON import file. All fields are raw strings. */
public record ImportRow(
        int rowNumber,
        String externalKey,
        String question,
        String answer,
        String topic,
        String subtopic,
        String jurisdiction,
        String riskLevel,
        String requiresHumanValidation,
        String sourceReference,
        String validFrom,
        String validTo,
        String notes
) {
}
