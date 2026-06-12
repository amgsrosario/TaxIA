package com.knowledgeflow.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "knowledgeflow.security.jwt")
public record JwtProperties(
        String issuer,
        String secret,
        long accessTokenTtlMinutes
) {
}
