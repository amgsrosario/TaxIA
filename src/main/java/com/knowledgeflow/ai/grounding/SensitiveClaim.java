package com.knowledgeflow.ai.grounding;

import java.util.List;

public record SensitiveClaim(
        String text,
        SensitiveClaimType type,
        boolean supported,
        List<String> supportingSourceTitles
) {}
