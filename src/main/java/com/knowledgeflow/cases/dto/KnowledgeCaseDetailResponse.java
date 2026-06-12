package com.knowledgeflow.cases.dto;

import com.knowledgeflow.cases.enums.KnowledgeCaseStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record KnowledgeCaseDetailResponse(
        UUID id,
        UUID organizationId,
        UUID clientId,
        String title,
        String question,
        String content,
        KnowledgeCaseStatus status,
        int currentVersionNumber,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
