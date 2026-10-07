package com.knowledgeflow.users.credentials;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Staff password policy (ADR-006): at least 12 characters, at most 72 UTF-8 bytes (the BCrypt
 * input limit — longer passwords are refused, never silently truncated), not blank, and not equal
 * to the account email. No artificial composition rules. "Different from the current password" is
 * checked by the caller against the stored hash.
 *
 * <p>Messages never echo the password.
 */
public final class StaffPasswordPolicy {

    public static final int MIN_CHARACTERS = 12;
    /** BCrypt only uses the first 72 bytes; refuse rather than truncate. */
    public static final int MAX_UTF8_BYTES = 72;

    private StaffPasswordPolicy() {
    }

    public static Optional<String> violation(String password, String email) {
        if (password == null || password.isBlank()) {
            return Optional.of("A nova password é obrigatória.");
        }
        if (password.codePointCount(0, password.length()) < MIN_CHARACTERS) {
            return Optional.of("A nova password deve ter pelo menos " + MIN_CHARACTERS + " caracteres.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            return Optional.of("A nova password excede o máximo de " + MAX_UTF8_BYTES + " bytes.");
        }
        if (email != null && password.strip().toLowerCase(Locale.ROOT)
                .equals(email.strip().toLowerCase(Locale.ROOT))) {
            return Optional.of("A nova password não pode ser igual ao email.");
        }
        return Optional.empty();
    }
}
