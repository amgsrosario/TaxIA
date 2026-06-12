package com.knowledgeflow.clients.dto;

import com.knowledgeflow.clients.enums.ClientStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ClientResponse(
        UUID id,
        UUID organizationId,
        String name,
        String taxIdentifier,
        String contactEmail,
        String phone,
        ClientStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
