package com.knowledgeflow.auth.dto;

import java.util.List;
import java.util.UUID;

public record CurrentUserResponse(
        UUID userId,
        UUID organizationId,
        String email,
        List<String> roles
) {
}
