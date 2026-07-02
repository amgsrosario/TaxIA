package com.knowledgeflow.common.health;

import com.knowledgeflow.ai.AIProperties;
import com.knowledgeflow.rag.EmbeddingProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Non-invasive health for the AI stack: reports configuration state only.
 * NEVER performs live (paid) calls to providers or the embeddings service —
 * a misconfigured provider degrades readiness, never liveness.
 */
@Component("aiStack")
public class AiStackHealthIndicator implements HealthIndicator {

    private final AIProperties aiProperties;
    private final EmbeddingProperties embeddingProperties;

    public AiStackHealthIndicator(AIProperties aiProperties, EmbeddingProperties embeddingProperties) {
        this.aiProperties = aiProperties;
        this.embeddingProperties = embeddingProperties;
    }

    @Override
    public Health health() {
        String primary = aiProperties.primaryProvider();
        var config = aiProperties.providers() != null && primary != null
                ? aiProperties.providers().get(primary)
                : null;

        boolean providerConfigured = config != null && config.enabled()
                && ("stub".equals(primary)
                        || (config.apiKey() != null && !config.apiKey().isBlank()));
        boolean embeddingsConfigured = embeddingProperties.baseUrl() != null
                && !embeddingProperties.baseUrl().isBlank();

        Health.Builder builder = providerConfigured && embeddingsConfigured
                ? Health.up()
                : Health.down();

        return builder
                .withDetail("primaryProvider", primary != null ? primary : "none")
                .withDetail("providerConfigured", providerConfigured)
                .withDetail("embeddingsConfigured", embeddingsConfigured)
                .withDetail("mode", "configuration-only (no live calls)")
                .build();
    }
}
