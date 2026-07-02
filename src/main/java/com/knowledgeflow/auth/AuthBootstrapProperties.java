package com.knowledgeflow.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls the one-shot bootstrap-admin endpoint.
 * Disabled by default; when enabled, a secret from the environment
 * (BOOTSTRAP_ADMIN_SECRET) is required and the operation only works while no
 * user exists — making it effectively single-use.
 */
@ConfigurationProperties(prefix = "knowledgeflow.auth.bootstrap")
public record AuthBootstrapProperties(
        boolean enabled,
        String secret
) {
    public boolean hasSecret() {
        return secret != null && !secret.isBlank();
    }
}
