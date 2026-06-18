package com.knowledgeflow.ai;

import com.knowledgeflow.ai.exception.AIConfigurationException;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Resolves the active AIProvider from the configured primary-provider name.
 * Centralises provider selection — no if/else chains elsewhere.
 */
@Service
public class AIProviderResolver {

    private final Map<String, AIProvider> providers;
    private final String primaryProvider;

    public AIProviderResolver(Map<String, AIProvider> providers, AIProperties properties) {
        if (properties.primaryProvider() == null || properties.primaryProvider().isBlank()) {
            throw new AIConfigurationException("knowledgeflow.ai.primary-provider is required but not configured");
        }
        this.providers = providers;
        this.primaryProvider = properties.primaryProvider();
    }

    public AIProvider resolve() {
        AIProvider provider = providers.get(primaryProvider);
        if (provider == null) {
            throw new AIConfigurationException(
                    "AI provider not found or not enabled: '" + primaryProvider + "'. " +
                    "Check knowledgeflow.ai.primary-provider and knowledgeflow.ai.providers.<name>.enabled");
        }
        return provider;
    }
}
