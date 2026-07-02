package com.knowledgeflow.ai.grounding;

public record AnswerSource(
        String title,
        String reference,
        double relevanceScore
) {}
