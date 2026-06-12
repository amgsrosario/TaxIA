package com.knowledgeflow.cases.dto;

import com.knowledgeflow.audit.enums.AuditAction;
import java.time.OffsetDateTime;
import java.util.UUID;

public record KnowledgeCaseCommentResponse(
        UUID id,
        UUID knowledgeCaseId,
        UUID createdByUserId,
        AuditAction workflowAction,
        String content,
        OffsetDateTime createdAt
) {
}
