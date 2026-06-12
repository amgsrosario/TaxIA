package com.knowledgeflow.security;

import java.util.List;
import java.util.UUID;

public record AuthenticatedUser(
        UUID userId,
        UUID organizationId,
        String email,
        List<String> roles
) {
}
