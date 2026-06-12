package com.knowledgeflow.clients.dto;

import com.knowledgeflow.clients.enums.ClientCategory;
import com.knowledgeflow.clients.enums.ClientStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ClientDetailResponse(
        UUID id,
        UUID organizationId,
        String name,
        String taxIdentifier,
        String contactEmail,
        String phone,
        String notes,
        ClientCategory category,
        String sector,
        String website,
        String addressLine,
        String city,
        String postalCode,
        String country,
        UUID relationshipManagerId,
        ClientStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
