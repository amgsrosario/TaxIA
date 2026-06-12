package com.knowledgeflow.interactions.controller;

import com.knowledgeflow.cases.dto.KnowledgeCaseDetailResponse;
import com.knowledgeflow.interactions.dto.AssistedInteractionAskRequest;
import com.knowledgeflow.interactions.dto.AssistedInteractionCreateRequest;
import com.knowledgeflow.interactions.dto.AssistedInteractionMessageResponse;
import com.knowledgeflow.interactions.dto.AssistedInteractionPromoteRequest;
import com.knowledgeflow.interactions.dto.AssistedInteractionResponse;
import com.knowledgeflow.interactions.service.AssistedInteractionService;
import com.knowledgeflow.security.AuthenticatedUser;
import com.knowledgeflow.security.AuthenticatedUserContext;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/interactions")
public class AssistedInteractionController {

    private final AssistedInteractionService service;
    private final AuthenticatedUserContext authenticatedUserContext;

    public AssistedInteractionController(AssistedInteractionService service,
                                          AuthenticatedUserContext authenticatedUserContext) {
        this.service = service;
        this.authenticatedUserContext = authenticatedUserContext;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR')")
    public ResponseEntity<AssistedInteractionResponse> create(
            @Valid @RequestBody AssistedInteractionCreateRequest request) {
        AuthenticatedUser user = authenticatedUserContext.getRequiredUser();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(user.organizationId(), user.userId(), request));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR', 'REVIEWER', 'VALIDATOR', 'VIEWER')")
    public ResponseEntity<Page<AssistedInteractionResponse>> list(
            @RequestParam(required = false) UUID clientId,
            Pageable pageable) {
        UUID organizationId = authenticatedUserContext.getRequiredUser().organizationId();
        return ResponseEntity.ok(service.list(organizationId, clientId, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR', 'REVIEWER', 'VALIDATOR', 'VIEWER')")
    public ResponseEntity<AssistedInteractionResponse> get(@PathVariable UUID id) {
        UUID organizationId = authenticatedUserContext.getRequiredUser().organizationId();
        return ResponseEntity.ok(service.get(organizationId, id));
    }

    @PostMapping("/{id}/ask")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR')")
    public ResponseEntity<AssistedInteractionMessageResponse> ask(
            @PathVariable UUID id,
            @Valid @RequestBody AssistedInteractionAskRequest request) {
        AuthenticatedUser user = authenticatedUserContext.getRequiredUser();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.ask(user.organizationId(), user.userId(), id, request));
    }

    @GetMapping("/{id}/messages")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR', 'REVIEWER', 'VALIDATOR', 'VIEWER')")
    public ResponseEntity<List<AssistedInteractionMessageResponse>> listMessages(@PathVariable UUID id) {
        UUID organizationId = authenticatedUserContext.getRequiredUser().organizationId();
        return ResponseEntity.ok(service.listMessages(organizationId, id));
    }

    @PostMapping("/{id}/promote")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR')")
    public ResponseEntity<KnowledgeCaseDetailResponse> promote(
            @PathVariable UUID id,
            @Valid @RequestBody AssistedInteractionPromoteRequest request) {
        AuthenticatedUser user = authenticatedUserContext.getRequiredUser();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.promote(user.organizationId(), user.userId(), id, request));
    }
}
