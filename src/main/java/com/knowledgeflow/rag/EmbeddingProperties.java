package com.knowledgeflow.rag;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "knowledgeflow.rag.embeddings")
public record EmbeddingProperties(
        String baseUrl,
        int topK,
        Duration connectTimeout,
        Duration readTimeout,
        Integer expectedDimension,
        Integer retryMaxAttempts,
        Duration retryBackoff
) {

    public Duration connectTimeoutOrDefault() {
        return connectTimeout != null ? connectTimeout : Duration.ofSeconds(3);
    }

    public Duration readTimeoutOrDefault() {
        return readTimeout != null ? readTimeout : Duration.ofSeconds(20);
    }

    /** Must match the vector(N) column dimension — 768 for the current model. */
    public int expectedDimensionOrDefault() {
        return expectedDimension != null ? expectedDimension : 768;
    }

    /** Total attempts (1 = no retry). Bounded: never more than 3. */
    public int retryMaxAttemptsOrDefault() {
        int value = retryMaxAttempts != null ? retryMaxAttempts : 3;
        return Math.min(Math.max(value, 1), 3);
    }

    public Duration retryBackoffOrDefault() {
        return retryBackoff != null ? retryBackoff : Duration.ofMillis(200);
    }
}
