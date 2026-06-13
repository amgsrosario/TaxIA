package com.knowledgeflow.portal;

import com.knowledgeflow.clients.dto.ClientPortalUserResponse;
import com.knowledgeflow.security.ClientAuthenticatedUser;
import com.knowledgeflow.security.ClientAuthenticatedUserContext;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Client-portal profile endpoints.
 * Authenticated via CLIENT_PORTAL JWT.
 */
@RestController
@RequestMapping("/api/v1/portal/me")
public class PortalProfileController {

    private final PortalProfileService portalProfileService;
    private final ClientAuthenticatedUserContext clientAuthenticatedUserContext;

    public PortalProfileController(PortalProfileService portalProfileService,
                                    ClientAuthenticatedUserContext clientAuthenticatedUserContext) {
        this.portalProfileService = portalProfileService;
        this.clientAuthenticatedUserContext = clientAuthenticatedUserContext;
    }

    /** Returns the profile of the currently authenticated portal user. */
    @GetMapping
    public ResponseEntity<ClientPortalUserResponse> getProfile() {
        ClientAuthenticatedUser user = clientAuthenticatedUserContext.getRequiredUser();
        return ResponseEntity.ok(
                portalProfileService.getProfile(user.portalUserId(), user.organizationId()));
    }

    /** Changes the password for the currently authenticated portal user. */
    @PutMapping("/password")
    public ResponseEntity<ClientPortalUserResponse> changePassword(
            @Valid @RequestBody PortalChangePasswordRequest request) {
        ClientAuthenticatedUser user = clientAuthenticatedUserContext.getRequiredUser();
        return ResponseEntity.ok(
                portalProfileService.changePassword(user.portalUserId(), user.organizationId(), request));
    }
}
