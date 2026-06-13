package com.knowledgeflow.clients.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ClientPortalAuthResponse(
        String accessToken,
        String tokenType,
        OffsetDateTime expiresAt,
        UUID portalUserId,
        UUID clientId,
        UUID organizationId,
        String email
) {}
