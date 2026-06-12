package com.knowledgeflow.clients.controller;

import com.knowledgeflow.clients.dto.ClientPortalUserCreateRequest;
import com.knowledgeflow.clients.dto.ClientPortalUserResponse;
import com.knowledgeflow.clients.service.ClientPortalUserService;
import com.knowledgeflow.security.AuthenticatedUserContext;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/clients/{clientId}/portal-users")
public class ClientPortalUserController {

    private final ClientPortalUserService clientPortalUserService;
    private final AuthenticatedUserContext authenticatedUserContext;

    public ClientPortalUserController(
            ClientPortalUserService clientPortalUserService,
            AuthenticatedUserContext authenticatedUserContext
    ) {
        this.clientPortalUserService = clientPortalUserService;
        this.authenticatedUserContext = authenticatedUserContext;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR')")
    public ResponseEntity<ClientPortalUserResponse> create(
            @PathVariable UUID clientId,
            @Valid @RequestBody ClientPortalUserCreateRequest request
    ) {
        UUID organizationId = authenticatedUserContext.getRequiredUser().organizationId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(clientPortalUserService.create(organizationId, clientId, request));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR', 'REVIEWER', 'VALIDATOR', 'VIEWER')")
    public ResponseEntity<List<ClientPortalUserResponse>> list(@PathVariable UUID clientId) {
        UUID organizationId = authenticatedUserContext.getRequiredUser().organizationId();
        return ResponseEntity.ok(clientPortalUserService.list(organizationId, clientId));
    }

    @DeleteMapping("/{portalUserId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deactivate(
            @PathVariable UUID clientId,
            @PathVariable UUID portalUserId
    ) {
        UUID organizationId = authenticatedUserContext.getRequiredUser().organizationId();
        clientPortalUserService.deactivate(organizationId, clientId, portalUserId);
        return ResponseEntity.noContent().build();
    }
}
