package com.knowledgeflow.billing.dto;

import java.time.Instant;
import java.util.UUID;

public record OrganizationPlanResponse(
        UUID id,
        UUID organizationId,
        CommercialPlanResponse plan,
        Instant startsAt,
        Instant endsAt,
        Instant createdAt
) {}
