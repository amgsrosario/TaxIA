package com.knowledgeflow.security;

/**
 * Authorities that encode the token type and the session state of an authenticated request
 * (ADR-006). They are granted only by {@link TokenTypeAwareJwtAuthenticationConverter} after the
 * per-request database checks, never read from a token claim.
 */
public final class StaffAuthorities {

    /** Staff (ORG_USER) session that passed every per-request check; roles are granted alongside. */
    public static final String STAFF = "TOKEN_ORG_USER";

    /**
     * Staff session whose user must change the password first (ADMIN reset / break-glass). No role
     * is granted: only the password-change flow and {@code /auth/me} are reachable.
     */
    public static final String STAFF_PASSWORD_CHANGE_ONLY = "TOKEN_ORG_USER_PASSWORD_CHANGE";

    /** Client portal (CLIENT_PORTAL) session; only portal routes are reachable. */
    public static final String CLIENT_PORTAL = "TOKEN_CLIENT_PORTAL";

    private StaffAuthorities() {
    }
}
