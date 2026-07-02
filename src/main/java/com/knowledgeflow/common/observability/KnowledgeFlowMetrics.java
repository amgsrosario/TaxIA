package com.knowledgeflow.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Essential business metrics (Micrometer). Tag values are limited to bounded
 * enumerations (provider, outcome, supportStatus, sourceKind) — never user,
 * organization or question identifiers, to avoid cardinality explosion.
 */
@Component
public class KnowledgeFlowMetrics {

    private final MeterRegistry registry;

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeFlowMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registry = registryProvider.getIfAvailable(SimpleMeterRegistry::new);
    }

    /** Test-friendly constructor. */
    public KnowledgeFlowMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ── AI provider ──────────────────────────────────────────────────────────

    public void recordAiRequest(String provider, String outcome, long durationMillis) {
        registry.counter("ai_requests_total",
                "provider", safe(provider), "outcome", safe(outcome)).increment();
        Timer.builder("ai_request_duration")
                .tag("provider", safe(provider))
                .register(registry)
                .record(Duration.ofMillis(durationMillis));
    }

    public void recordAiProviderError(String provider, String errorType) {
        registry.counter("ai_provider_errors_total",
                "provider", safe(provider), "outcome", safe(errorType)).increment();
    }

    // ── Grounding ────────────────────────────────────────────────────────────

    public void recordGroundingOutcome(String supportStatus, boolean responseRejected) {
        registry.counter("grounding_outcomes_total",
                "supportStatus", safe(supportStatus)).increment();
        if (responseRejected) {
            registry.counter("grounding_rejections_total").increment();
        }
        if ("INSUFFICIENT_CONTEXT".equals(supportStatus)) {
            registry.counter("grounding_insufficient_context_total").increment();
        }
    }

    // ── Embeddings ───────────────────────────────────────────────────────────

    public void recordEmbeddingRequest(String outcome) {
        registry.counter("embedding_requests_total", "outcome", safe(outcome)).increment();
        if (!"success".equals(outcome)) {
            registry.counter("embedding_failures_total", "outcome", safe(outcome)).increment();
        }
    }

    // ── RAG ──────────────────────────────────────────────────────────────────

    public void recordRagQuery(long durationMillis) {
        registry.counter("rag_queries_total").increment();
        Timer.builder("rag_query_duration")
                .register(registry)
                .record(Duration.ofMillis(durationMillis));
    }

    // ── Knowledge publication ────────────────────────────────────────────────

    public void recordPublication(String outcome) {
        registry.counter("knowledge_publications_total", "outcome", safe(outcome)).increment();
        if (!"success".equals(outcome)) {
            registry.counter("knowledge_publication_failures_total").increment();
        }
    }

    private static String safe(String tag) {
        return tag != null && !tag.isBlank() ? tag : "unknown";
    }
}
