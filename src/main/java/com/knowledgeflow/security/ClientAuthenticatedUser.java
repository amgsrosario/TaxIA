package com.knowledgeflow.security;

import java.util.UUID;

/**
 * Principal extracted from a CLIENT_PORTAL JWT token.
 * Distinct from {@link AuthenticatedUser} which represents org staff.
 */
public record ClientAuthenticatedUser(
        UUID portalUserId,
        UUID clientId,
        UUID organizationId,
        String email
) {}
