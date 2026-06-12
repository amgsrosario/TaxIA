package com.knowledgeflow.security;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class AuthenticatedUserContext {

    public AuthenticatedUser getRequiredUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("Authenticated JWT principal is required");
        }

        return new AuthenticatedUser(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString("organization_id")),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsStringList("roles") == null ? List.of() : jwt.getClaimAsStringList("roles")
        );
    }
}
