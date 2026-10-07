package com.knowledgeflow.users.credentials;

import java.util.UUID;

/**
 * Audit metadata for credential/session events (ADR-006): actor, target, organization and, when
 * applicable, the reason. Never a password, hash, JWT or secret.
 */
final class CredentialAuditMetadata {

    static final int MAX_REASON_LENGTH = 500;

    private CredentialAuditMetadata() {
    }

    static String of(String actor, UUID target, UUID organization, String reason) {
        return of(actor, target, organization, reason, new String[0]);
    }

    /**
     * {@code extras} are trusted {@code key=value} pairs written before the reason; the
     * free-text reason is always the last key and cannot inject separators.
     */
    static String of(String actor, UUID target, UUID organization, String reason, String... extras) {
        StringBuilder sb = new StringBuilder()
                .append("actor=").append(actor)
                .append(";target=").append(target)
                .append(";organization=").append(organization);
        for (String extra : extras) {
            sb.append(';').append(extra);
        }
        if (reason != null && !reason.isBlank()) {
            sb.append(";reason=").append(sanitize(reason));
        }
        return sb.toString();
    }

    /** One line, bounded length, no metadata separators ({@code ;} and {@code =}). */
    static String sanitize(String reason) {
        String oneLine = reason.strip().replaceAll("[\\r\\n\\t]+", " ").replace(';', ',').replace('=', ':');
        return oneLine.length() > MAX_REASON_LENGTH ? oneLine.substring(0, MAX_REASON_LENGTH) : oneLine;
    }
}
