package com.knowledgeflow.ingestion.atfaq;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality metrics for the AT FAQ pilot. Tags are limited to
 * {@code outcome}, {@code category} and {@code httpStatusClass} — never URLs
 * or official FAQ ids.
 */
@Component
public class AtFaqMetrics {

    private final MeterRegistry registry;

    @org.springframework.beans.factory.annotation.Autowired
    public AtFaqMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registry = registryProvider.getIfAvailable(SimpleMeterRegistry::new);
    }

    /** Test-friendly constructor. */
    public AtFaqMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordHttpRequest(int statusCode) {
        registry.counter("at_faq_http_requests_total",
                "httpStatusClass", statusClass(statusCode)).increment();
    }

    public void recordPagesDiscovered(int count) {
        registry.counter("at_faq_pages_discovered_total").increment(count);
    }

    public void recordPageParsed(String category) {
        registry.counter("at_faq_pages_parsed_total", "category", safe(category)).increment();
    }

    public void recordParseFailure(String category) {
        registry.counter("at_faq_parse_failures_total", "category", safe(category)).increment();
    }

    public void recordItemNew(String category) {
        registry.counter("at_faq_items_new_total", "category", safe(category)).increment();
    }

    public void recordItemChanged(String category) {
        registry.counter("at_faq_items_changed_total", "category", safe(category)).increment();
    }

    public void recordItemUnchanged(String category) {
        registry.counter("at_faq_items_unchanged_total", "category", safe(category)).increment();
    }

    public void recordItemRemovedCandidate(String category) {
        registry.counter("at_faq_items_removed_candidate_total", "category", safe(category)).increment();
    }

    public void recordIngestionDuration(String outcome, long durationMillis) {
        Timer.builder("at_faq_ingestion_duration")
                .tag("outcome", safe(outcome))
                .register(registry)
                .record(Duration.ofMillis(durationMillis));
    }

    private static String statusClass(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) return "2xx";
        if (statusCode >= 300 && statusCode < 400) return "3xx";
        if (statusCode >= 400 && statusCode < 500) return "4xx";
        if (statusCode >= 500 && statusCode < 600) return "5xx";
        return "other";
    }

    private static String safe(String tag) {
        return tag != null && !tag.isBlank() ? tag : "unknown";
    }
}
