package com.knowledgeflow.cases.dto;

import com.knowledgeflow.cases.enums.KnowledgeCaseStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record KnowledgeCaseResponse(
        UUID id,
        UUID organizationId,
        UUID clientId,
        String title,
        KnowledgeCaseStatus status,
        int currentVersionNumber,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
