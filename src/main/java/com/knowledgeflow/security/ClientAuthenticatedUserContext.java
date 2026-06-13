package com.knowledgeflow.security;

import com.knowledgeflow.auth.service.JwtService;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Extracts the {@link ClientAuthenticatedUser} principal from the current security context.
 * Only valid when the request was authenticated with a CLIENT_PORTAL token.
 */
@Component
public class ClientAuthenticatedUserContext {

    public ClientAuthenticatedUser getRequiredUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("Authenticated JWT principal is required");
        }

        String tokenType = jwt.getClaimAsString("token_type");
        if (!JwtService.TOKEN_TYPE_CLIENT_PORTAL.equals(tokenType)) {
            throw new IllegalStateException(
                    "Expected CLIENT_PORTAL token but got: " + tokenType);
        }

        return new ClientAuthenticatedUser(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString("client_id")),
                UUID.fromString(jwt.getClaimAsString("organization_id")),
                jwt.getClaimAsString("email")
        );
    }
}
