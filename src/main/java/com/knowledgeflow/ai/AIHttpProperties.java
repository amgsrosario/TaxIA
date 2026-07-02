package com.knowledgeflow.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HTTP resilience configuration for the AI provider clients.
 *
 * Timeouts are explicit per provider (no infinite defaults); the retry policy
 * is shared and bounded — see docs/backend-resilience-observability.md.
 */
@ConfigurationProperties(prefix = "knowledgeflow.ai.http")
public record AIHttpProperties(
        ClientTimeouts openai,
        ClientTimeouts anthropic,
        Retry retry
) {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(60);

    public ClientTimeouts openaiOrDefault() {
        return openai != null ? openai : new ClientTimeouts(null, null);
    }

    public ClientTimeouts anthropicOrDefault() {
        return anthropic != null ? anthropic : new ClientTimeouts(null, null);
    }

    public Retry retryOrDefault() {
        return retry != null ? retry : new Retry(null, null);
    }

    public record ClientTimeouts(
            Duration connectTimeout,
            Duration readTimeout
    ) {
        public Duration connectTimeoutOrDefault() {
            return connectTimeout != null ? connectTimeout : DEFAULT_CONNECT_TIMEOUT;
        }

        public Duration readTimeoutOrDefault() {
            return readTimeout != null ? readTimeout : DEFAULT_READ_TIMEOUT;
        }
    }

    public record Retry(
            Integer maxAttempts,
            Duration backoff
    ) {
        /** Total attempts (1 = no retry). Bounded: never more than 3. */
        public int maxAttemptsOrDefault() {
            int value = maxAttempts != null ? maxAttempts : 3;
            return Math.min(Math.max(value, 1), 3);
        }

        public Duration backoffOrDefault() {
            return backoff != null ? backoff : Duration.ofMillis(300);
        }
    }
}
