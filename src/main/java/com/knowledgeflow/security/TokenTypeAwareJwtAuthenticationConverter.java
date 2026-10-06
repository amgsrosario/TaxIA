package com.knowledgeflow.security;

import com.knowledgeflow.auth.service.JwtService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Turns a signature/expiry/issuer-validated JWT into an authentication (ADR-006).
 *
 * <ul>
 *   <li>{@code token_type=ORG_USER}: requires an integral, non-negative {@code tv} claim, then
 *       checks the user, membership and token version in the database
 *       ({@link StaffSessionVerifier}). Authorities are {@link StaffAuthorities#STAFF} plus the
 *       {@code ROLE_*} of the current database roles, or only
 *       {@link StaffAuthorities#STAFF_PASSWORD_CHANGE_ONLY} while a password change is required.
 *       The {@code roles} claim is never a source of authority.</li>
 *   <li>{@code token_type=CLIENT_PORTAL}: unchanged portal model, authority
 *       {@link StaffAuthorities#CLIENT_PORTAL} only.</li>
 *   <li>Anything else (missing/unknown type, legacy staff token without {@code tv}, stale
 *       {@code tv}, disabled/deleted user, no membership): {@link InvalidBearerTokenException},
 *       i.e. HTTP 401.</li>
 * </ul>
 */
public class TokenTypeAwareJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String TOKEN_VERSION_CLAIM = "tv";

    private final StaffSessionVerifier staffSessionVerifier;

    public TokenTypeAwareJwtAuthenticationConverter(StaffSessionVerifier staffSessionVerifier) {
        this.staffSessionVerifier = staffSessionVerifier;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Object tokenType = jwt.getClaims().get("token_type");
        if (JwtService.TOKEN_TYPE_ORG.equals(tokenType)) {
            return staff(jwt);
        }
        if (JwtService.TOKEN_TYPE_CLIENT_PORTAL.equals(tokenType)) {
            return new JwtAuthenticationToken(jwt,
                    List.of(new SimpleGrantedAuthority(StaffAuthorities.CLIENT_PORTAL)), jwt.getSubject());
        }
        throw invalid();
    }

    private AbstractAuthenticationToken staff(Jwt jwt) {
        UUID userId = uuidClaim(jwt.getClaims().get("sub"));
        UUID organizationId = uuidClaim(jwt.getClaims().get("organization_id"));
        long tokenVersion = tokenVersion(jwt.getClaims().get(TOKEN_VERSION_CLAIM));

        StaffSessionVerifier.VerifiedStaffSession session = staffSessionVerifier
                .verify(userId, organizationId, tokenVersion)
                .orElseThrow(TokenTypeAwareJwtAuthenticationConverter::invalid);

        List<GrantedAuthority> authorities = new ArrayList<>();
        if (session.mustChangePassword()) {
            authorities.add(new SimpleGrantedAuthority(StaffAuthorities.STAFF_PASSWORD_CHANGE_ONLY));
        } else {
            authorities.add(new SimpleGrantedAuthority(StaffAuthorities.STAFF));
            session.roles().forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        }
        return new JwtAuthenticationToken(jwt, authorities, userId.toString());
    }

    /** Only a JSON integer in [0, Integer.MAX_VALUE] is accepted: no string, fraction or negative. */
    static long tokenVersion(Object raw) {
        if (!(raw instanceof Integer || raw instanceof Long)) {
            throw invalid();
        }
        long value = ((Number) raw).longValue();
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw invalid();
        }
        return value;
    }

    private static UUID uuidClaim(Object raw) {
        if (!(raw instanceof String value)) {
            throw invalid();
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static InvalidBearerTokenException invalid() {
        // Deliberately generic: the response never says which check failed.
        return new InvalidBearerTokenException("Invalid or revoked token");
    }
}
