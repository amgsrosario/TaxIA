package com.knowledgeflow.security;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Minimum requirements for the HS256 signing secret (ADR-006). Messages never contain the value.
 */
public final class JwtSecretPolicy {

    /**
     * The development default that used to ship in application.yml and .env.example. It is public
     * (versioned in the repository), so anyone who knows it can forge tokens: always refused.
     */
    static final String PUBLIC_DEVELOPMENT_DEFAULT =
            "change-this-development-secret-change-this-development-secret";

    /** HS256 key size: at least 256 bits. */
    public static final int MIN_BYTES = 32;

    /** A secret made of very few distinct characters is not random, whatever its length. */
    static final int MIN_DISTINCT_CHARACTERS = 10;

    private JwtSecretPolicy() {
    }

    /**
     * Marker carried by every fictitious signing key in the test resources. Those keys are public
     * (versioned), so they are refused outside the automated test profiles.
     */
    static final String TEST_KEY_MARKER = "not-a-secret";

    /** Returns why the secret is unacceptable, or empty when it satisfies the policy. */
    public static Optional<String> violation(String secret) {
        return violation(secret, false);
    }

    /**
     * Same as {@link #violation(String)}; {@code allowTestKeys} admits the public fictitious test
     * keys and is true only under the automated test profiles.
     */
    public static Optional<String> violation(String secret, boolean allowTestKeys) {
        if (secret == null || secret.isBlank()) {
            return Optional.of("KNOWLEDGEFLOW_JWT_SECRET is not set");
        }
        if (PUBLIC_DEVELOPMENT_DEFAULT.equals(secret.strip())) {
            return Optional.of("KNOWLEDGEFLOW_JWT_SECRET is the public development default");
        }
        if (!allowTestKeys && secret.toLowerCase(java.util.Locale.ROOT).contains(TEST_KEY_MARKER)) {
            return Optional.of("KNOWLEDGEFLOW_JWT_SECRET is a public test-only key");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_BYTES) {
            return Optional.of("KNOWLEDGEFLOW_JWT_SECRET is shorter than " + MIN_BYTES + " bytes");
        }
        if (secret.chars().distinct().count() < MIN_DISTINCT_CHARACTERS) {
            return Optional.of("KNOWLEDGEFLOW_JWT_SECRET is not random enough (too few distinct characters)");
        }
        return Optional.empty();
    }
}
