package com.knowledgeflow.clients.dto;

import com.knowledgeflow.clients.enums.ClientPortalUserStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ClientPortalUserResponse(
        UUID id,
        UUID clientId,
        UUID organizationId,
        String email,
        ClientPortalUserStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
