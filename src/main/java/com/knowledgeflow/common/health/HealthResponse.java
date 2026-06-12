package com.knowledgeflow.common.health;

import java.time.OffsetDateTime;

public record HealthResponse(
        String status,
        OffsetDateTime timestamp
) {
}
