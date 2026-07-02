package com.knowledgeflow.ai.grounding;

import java.util.List;

public record ContextSufficiencyAssessment(
        AnswerSupportStatus status,
        String reason,
        List<String> missingElements,
        int distinctSourceCount,
        int fragmentCount,
        double bestRelevanceScore,
        boolean humanReviewRequired
) {
    public boolean isSufficient() {
        return status == AnswerSupportStatus.SUPPORTED
                || status == AnswerSupportStatus.REQUIRES_HUMAN_REVIEW;
    }
}
