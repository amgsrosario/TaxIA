package com.knowledgeflow.ai;

import com.knowledgeflow.ai.exception.AIConfigurationException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.TreeSet;

/**
 * Resolves the active AIProvider from the configured primary-provider name.
 * Validates that the configured provider is available at startup — not on the first request.
 */
@Service
public class AIProviderResolver {

    private final Map<String, AIProvider> providers;
    private final String primaryProvider;

    public AIProviderResolver(Map<String, AIProvider> providers, AIProperties properties) {
        String primary = properties.primaryProvider();
        if (primary == null || primary.isBlank()) {
            throw new AIConfigurationException(
                    "knowledgeflow.ai.primary-provider is required but not configured.");
        }
        primary = primary.trim();
        if (!providers.containsKey(primary)) {
            String available = providers.isEmpty()
                    ? "(nenhum provider disponível)"
                    : String.join(", ", new TreeSet<>(providers.keySet()));
            throw new AIConfigurationException(
                    "O provider principal '" + primary + "' não está disponível. " +
                    "Providers registados: " + available + ".");
        }
        this.providers = providers;
        this.primaryProvider = primary;
    }

    public AIProvider resolve() {
        return providers.get(primaryProvider);
    }
}
