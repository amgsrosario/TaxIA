package com.knowledgeflow.clients.controller;

import com.knowledgeflow.clients.dto.ClientCreateRequest;
import com.knowledgeflow.clients.dto.ClientDetailResponse;
import com.knowledgeflow.clients.dto.ClientResponse;
import com.knowledgeflow.clients.dto.ClientUpdateRequest;
import com.knowledgeflow.clients.enums.ClientStatus;
import com.knowledgeflow.clients.service.ClientService;
import com.knowledgeflow.security.AuthenticatedUser;
import com.knowledgeflow.security.AuthenticatedUserContext;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/clients")
public class ClientController {

    private final ClientService clientService;
    private final AuthenticatedUserContext authenticatedUserContext;

    public ClientController(ClientService clientService, AuthenticatedUserContext authenticatedUserContext) {
        this.clientService = clientService;
        this.authenticatedUserContext = authenticatedUserContext;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR')")
    public ResponseEntity<ClientDetailResponse> create(@Valid @RequestBody ClientCreateRequest request) {
        AuthenticatedUser user = authenticatedUserContext.getRequiredUser();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(clientService.create(user.organizationId(), user.userId(), request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR', 'REVIEWER', 'VALIDATOR', 'VIEWER')")
    public ResponseEntity<ClientDetailResponse> get(@PathVariable UUID id) {
        UUID organizationId = authenticatedUserContext.getRequiredUser().organizationId();
        return ResponseEntity.ok(clientService.get(organizationId, id));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR', 'REVIEWER', 'VALIDATOR', 'VIEWER')")
    public ResponseEntity<Page<ClientResponse>> list(
            @RequestParam(required = false) ClientStatus status,
            @PageableDefault(size = 20, sort = "name") Pageable pageable
    ) {
        UUID organizationId = authenticatedUserContext.getRequiredUser().organizationId();
        return ResponseEntity.ok(clientService.list(organizationId, status, pageable));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR')")
    public ResponseEntity<ClientDetailResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody ClientUpdateRequest request
    ) {
        AuthenticatedUser user = authenticatedUserContext.getRequiredUser();
        return ResponseEntity.ok(clientService.update(user.organizationId(), user.userId(), id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> archive(@PathVariable UUID id) {
        AuthenticatedUser user = authenticatedUserContext.getRequiredUser();
        clientService.archive(user.organizationId(), user.userId(), id);
        return ResponseEntity.noContent().build();
    }
}
