package com.knowledgeflow.ai.grounding;

import java.util.List;

public record GroundedAIResponse(
        String answer,
        AnswerSupportStatus supportStatus,
        String supportReason,
        List<AnswerSource> sources,
        List<String> missingInformation,
        List<String> limitations,
        boolean requiresHumanValidation,
        String validationMessage,
        boolean providerCalled,
        boolean responseRejected,
        String rejectionReason,
        int unsupportedClaimsCount,
        String provider,
        String model,
        int inputTokens,
        int outputTokens,
        long durationMillis
) {}
