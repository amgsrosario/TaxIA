package com.knowledgeflow.billing.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record AssignPlanRequest(
        @NotNull UUID planId,
        @NotNull Instant startsAt,
        /** Null = open-ended */
        Instant endsAt
) {}
