package com.knowledgeflow.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "knowledgeflow.ai")
public record AIProperties(
        String primaryProvider,
        Map<String, ProviderConfig> providers
) {
    public record ProviderConfig(
            boolean enabled,
            String apiKey,
            String model,
            Integer maxTokens
    ) {}
}
