package com.knowledgeflow.security;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * The authenticated staff user of the current request (ADR-006).
 *
 * <p>Only a staff session is accepted: a CLIENT_PORTAL token (or any authentication without a
 * staff authority) is refused with {@link AccessDeniedException}. Roles come from the
 * authorities granted after the per-request database check — never from the token's
 * {@code roles} claim.
 */
@Component
public class AuthenticatedUserContext {

    private static final String ROLE_PREFIX = "ROLE_";

    public AuthenticatedUser getRequiredUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("Authenticated JWT principal is required");
        }
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        if (!hasAuthority(authorities, StaffAuthorities.STAFF)
                && !hasAuthority(authorities, StaffAuthorities.STAFF_PASSWORD_CHANGE_ONLY)) {
            throw new AccessDeniedException("A staff session is required");
        }

        List<String> roles = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(ROLE_PREFIX))
                .map(a -> a.substring(ROLE_PREFIX.length()))
                .sorted()
                .toList();

        return new AuthenticatedUser(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString("organization_id")),
                jwt.getClaimAsString("email"),
                roles
        );
    }

    /** True while the session is restricted to the password change (ADMIN reset / break-glass). */
    public boolean isPasswordChangeRequired() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && hasAuthority(authentication.getAuthorities(), StaffAuthorities.STAFF_PASSWORD_CHANGE_ONLY);
    }

    /** The {@code tv} claim of the current staff token (already validated by the converter). */
    public long requiredTokenVersion() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("Authenticated JWT principal is required");
        }
        Object raw = jwt.getClaims().get(TokenTypeAwareJwtAuthenticationConverter.TOKEN_VERSION_CLAIM);
        return TokenTypeAwareJwtAuthenticationConverter.tokenVersion(raw);
    }

    private static boolean hasAuthority(Collection<? extends GrantedAuthority> authorities, String authority) {
        return authorities.stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
