package com.knowledgeflow.auth.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AuthResponse(
        String accessToken,
        String tokenType,
        OffsetDateTime expiresAt,
        UUID userId,
        UUID organizationId,
        String email,
        String fullName,
        List<String> roles
) {
}
