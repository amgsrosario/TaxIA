package com.knowledgeflow.users.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** ADR-006 password policy: length, BCrypt byte limit without truncation, not the email. */
class StaffPasswordPolicyTest {

    private static final String EMAIL = "pessoa@taxia.local";

    @Test
    void acceptsTwelveCharactersWithoutCompositionRules() {
        assertThat(StaffPasswordPolicy.violation("abcdefghijkl", EMAIL)).isEmpty();
        assertThat(StaffPasswordPolicy.violation("frase longa só com letras", EMAIL)).isEmpty();
    }

    @Test
    void refusesShortBlankOrNull() {
        assertThat(StaffPasswordPolicy.violation("abcdefghijk", EMAIL)).isPresent();
        assertThat(StaffPasswordPolicy.violation("            ", EMAIL)).isPresent();
        assertThat(StaffPasswordPolicy.violation(null, EMAIL)).isPresent();
    }

    @Test
    void countsCharactersNotBytesForTheMinimum() {
        // 12 code points, more than 12 bytes; 11 emoji (22 chars in UTF-16) are still 11 characters.
        assertThat(StaffPasswordPolicy.violation("ççççççççççç1", EMAIL)).isEmpty();
        assertThat(StaffPasswordPolicy.violation("😀".repeat(11), EMAIL)).isPresent();
    }

    @Test
    void refusesMoreThan72BytesInsteadOfTruncating() {
        assertThat(StaffPasswordPolicy.violation("a".repeat(72), EMAIL)).isEmpty();
        assertThat(StaffPasswordPolicy.violation("a".repeat(73), EMAIL)).isPresent();
        // 37 two-byte characters = 74 bytes.
        assertThat(StaffPasswordPolicy.violation("ç".repeat(37), EMAIL)).isPresent();
    }

    @Test
    void refusesTheEmailIgnoringCaseAndSurroundingSpaces() {
        assertThat(StaffPasswordPolicy.violation("PESSOA@taxia.local", EMAIL)).isPresent();
        assertThat(StaffPasswordPolicy.violation("  pessoa@taxia.local  ", EMAIL)).isPresent();
    }

    @Test
    void messagesNeverEchoThePassword() {
        String candidate = "segredo-curt";
        StaffPasswordPolicy.violation(candidate.substring(0, 10), EMAIL)
                .ifPresent(m -> assertThat(m).doesNotContain(candidate.substring(0, 10)));
        StaffPasswordPolicy.violation("z".repeat(80), EMAIL)
                .ifPresent(m -> assertThat(m).doesNotContain("zzzz"));
    }
}
