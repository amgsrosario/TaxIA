package com.knowledgeflow.ai.grounding;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "knowledgeflow.grounding")
public record GroundingProperties(
        boolean enabled,
        int minimumFragments,
        int minimumDistinctSources,
        double minimumRelevanceScore,
        boolean rejectUnsupportedSensitiveClaims,
        boolean skipProviderWhenContextInsufficient
) {}
