package com.knowledgeflow.billing.dto;

import java.time.Instant;
import java.util.UUID;

public record ConsumptionSummaryResponse(
        UUID organizationId,
        Instant periodStart,
        Instant periodEnd,
        long casesCreated,
        long aiInteractions,
        Integer maxCases,
        Integer maxInteractions
) {}
