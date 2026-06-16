package com.knowledgeflow.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "knowledgeflow.ai.anthropic")
public record AnthropicProperties(
        String apiKey,
        String model,
        int maxTokens
) {}
