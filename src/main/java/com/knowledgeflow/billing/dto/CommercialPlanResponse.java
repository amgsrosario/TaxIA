package com.knowledgeflow.billing.dto;

import com.knowledgeflow.billing.enums.PlanType;
import java.util.UUID;

public record CommercialPlanResponse(
        UUID id,
        String name,
        PlanType planType,
        Integer maxCases,
        Integer maxInteractions,
        Integer maxPortalUsers,
        boolean active
) {}
