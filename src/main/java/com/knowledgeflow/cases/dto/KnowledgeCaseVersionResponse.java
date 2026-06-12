package com.knowledgeflow.cases.dto;

import com.knowledgeflow.cases.enums.KnowledgeCaseStatus;
import com.knowledgeflow.cases.enums.KnowledgeCaseVersionSourceType;
import java.time.OffsetDateTime;
import java.util.UUID;

public record KnowledgeCaseVersionResponse(
        UUID id,
        UUID knowledgeCaseId,
        int versionNumber,
        KnowledgeCaseVersionSourceType sourceType,
        String title,
        String question,
        String content,
        KnowledgeCaseStatus statusSnapshot,
        boolean validatedVersion,
        UUID createdByUserId,
        OffsetDateTime createdAt
) {
}
