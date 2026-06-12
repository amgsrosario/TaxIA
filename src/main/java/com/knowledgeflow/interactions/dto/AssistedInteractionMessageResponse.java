package com.knowledgeflow.interactions.dto;

import java.time.Instant;
import java.util.UUID;

public record AssistedInteractionMessageResponse(
        UUID id,
        UUID interactionId,
        String question,
        String answer,
        String modelUsed,
        int inputTokens,
        int outputTokens,
        Instant createdAt
) {}
