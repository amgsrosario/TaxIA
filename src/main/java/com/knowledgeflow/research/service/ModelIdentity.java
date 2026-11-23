package com.knowledgeflow.research.service;

import com.knowledgeflow.ai.AIProperties;
import org.springframework.stereotype.Component;

/**
 * Identifica o provider e o modelo configurados, para registar chamadas que falham antes de haver
 * resposta. Só lê o identificador e o nome do modelo; nunca a credencial.
 */
@Component
public class ModelIdentity {

    public record Identity(String provider, String model) {}

    private final AIProperties properties;

    public ModelIdentity(AIProperties properties) {
        this.properties = properties;
    }

    public Identity current() {
        String provider = properties.primaryProvider() == null ? "unknown" : properties.primaryProvider().trim();
        AIProperties.ProviderConfig config = properties.providers() == null ? null : properties.providers().get(provider);
        String model = config == null || config.model() == null || config.model().isBlank() ? "unknown" : config.model();
        return new Identity(provider, model);
    }
}
