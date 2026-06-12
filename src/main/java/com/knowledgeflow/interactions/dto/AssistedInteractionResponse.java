package com.knowledgeflow.interactions.dto;

import com.knowledgeflow.interactions.enums.AssistedInteractionStatus;
import java.time.Instant;
import java.util.UUID;

public record AssistedInteractionResponse(
        UUID id,
        UUID organizationId,
        UUID clientId,
        UUID createdByUserId,
        String title,
        AssistedInteractionStatus status,
        UUID promotedCaseId,
        Instant createdAt,
        Instant updatedAt
) {}
