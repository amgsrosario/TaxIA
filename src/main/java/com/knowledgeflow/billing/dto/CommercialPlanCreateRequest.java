package com.knowledgeflow.billing.dto;

import com.knowledgeflow.billing.enums.PlanType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CommercialPlanCreateRequest(
        @NotBlank String name,
        @NotNull PlanType planType,
        /** Null = unlimited */
        Integer maxCases,
        /** Null = unlimited */
        Integer maxInteractions,
        /** Null = unlimited */
        Integer maxPortalUsers
) {}
