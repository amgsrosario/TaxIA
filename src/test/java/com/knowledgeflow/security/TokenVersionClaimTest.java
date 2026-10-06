package com.knowledgeflow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/** ADR-006 parsing of the {@code tv} claim: only a JSON integer in [0, Integer.MAX_VALUE]. */
class TokenVersionClaimTest {

    @Test
    void acceptsIntegralNonNegative() {
        assertThat(TokenTypeAwareJwtAuthenticationConverter.tokenVersion(0L)).isZero();
        assertThat(TokenTypeAwareJwtAuthenticationConverter.tokenVersion(7)).isEqualTo(7);
        assertThat(TokenTypeAwareJwtAuthenticationConverter.tokenVersion((long) Integer.MAX_VALUE))
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void refusesEverythingElse() {
        for (Object raw : new Object[] {null, "0", 0.0d, 1.5d, -1L, (long) Integer.MAX_VALUE + 1,
                BigInteger.ONE, true}) {
            assertThatThrownBy(() -> TokenTypeAwareJwtAuthenticationConverter.tokenVersion(raw))
                    .isInstanceOf(InvalidBearerTokenException.class);
        }
    }
}
