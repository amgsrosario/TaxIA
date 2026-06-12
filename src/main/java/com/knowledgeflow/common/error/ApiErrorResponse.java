package com.knowledgeflow.common.error;

import java.time.OffsetDateTime;
import java.util.Map;

public record ApiErrorResponse(
        String code,
        String message,
        String path,
        OffsetDateTime timestamp,
        Map<String, Object> details
) {
}
