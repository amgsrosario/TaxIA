package com.knowledgeflow.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CORS configuration by property — no hardcoded wildcard.
 * Production must list explicit origins; "*" is only honoured as an explicit
 * opt-in and never together with credentials.
 */
@ConfigurationProperties(prefix = "knowledgeflow.security.cors")
public record CorsProperties(
        List<String> allowedOrigins,
        List<String> allowedMethods,
        List<String> allowedHeaders,
        boolean allowCredentials
) {

    public List<String> allowedOriginsOrDefault() {
        return allowedOrigins != null && !allowedOrigins.isEmpty()
                ? allowedOrigins
                : List.of("http://localhost:3000", "http://localhost:5173", "http://localhost:8081");
    }

    public List<String> allowedMethodsOrDefault() {
        return allowedMethods != null && !allowedMethods.isEmpty()
                ? allowedMethods
                : List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
    }

    public List<String> allowedHeadersOrDefault() {
        return allowedHeaders != null && !allowedHeaders.isEmpty()
                ? allowedHeaders
                : List.of("Authorization", "Content-Type", "X-Correlation-ID");
    }

    public boolean isWildcard() {
        return allowedOriginsOrDefault().contains("*");
    }
}
