package com.knowledgeflow.portal;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for a client-portal-initiated assistive interaction.
 * The clientId is derived from the JWT; no org user is involved.
 */
public record PortalInteractionCreateRequest(
        @NotBlank String title
) {}
