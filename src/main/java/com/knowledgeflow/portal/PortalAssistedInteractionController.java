package com.knowledgeflow.portal;

import com.knowledgeflow.interactions.dto.AssistedInteractionAskRequest;
import com.knowledgeflow.interactions.dto.AssistedInteractionMessageResponse;
import com.knowledgeflow.interactions.dto.AssistedInteractionResponse;
import com.knowledgeflow.security.ClientAuthenticatedUser;
import com.knowledgeflow.security.ClientAuthenticatedUserContext;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Client-portal endpoints for assistive interactions.
 * Authenticated via CLIENT_PORTAL JWT — no org user, no role checks.
 */
@RestController
@RequestMapping("/api/v1/portal/interactions")
public class PortalAssistedInteractionController {

    private final PortalAssistedInteractionService portalInteractionService;
    private final ClientAuthenticatedUserContext clientAuthenticatedUserContext;

    public PortalAssistedInteractionController(
            PortalAssistedInteractionService portalInteractionService,
            ClientAuthenticatedUserContext clientAuthenticatedUserContext) {
        this.portalInteractionService = portalInteractionService;
        this.clientAuthenticatedUserContext = clientAuthenticatedUserContext;
    }

    /** Start a new assistive interaction session. */
    @PostMapping
    public ResponseEntity<AssistedInteractionResponse> create(
            @Valid @RequestBody PortalInteractionCreateRequest request) {
        ClientAuthenticatedUser user = clientAuthenticatedUserContext.getRequiredUser();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(portalInteractionService.create(
                        user.clientId(), user.organizationId(), request.title()));
    }

    /** List all interactions for the authenticated client. */
    @GetMapping
    public ResponseEntity<Page<AssistedInteractionResponse>> list(
            @PageableDefault(size = 20) Pageable pageable) {
        ClientAuthenticatedUser user = clientAuthenticatedUserContext.getRequiredUser();
        return ResponseEntity.ok(
                portalInteractionService.list(user.clientId(), user.organizationId(), pageable));
    }

    /** Ask a question in an existing interaction. */
    @PostMapping("/{id}/ask")
    public ResponseEntity<AssistedInteractionMessageResponse> ask(
            @PathVariable UUID id,
            @Valid @RequestBody AssistedInteractionAskRequest request) {
        ClientAuthenticatedUser user = clientAuthenticatedUserContext.getRequiredUser();
        return ResponseEntity.ok(
                portalInteractionService.ask(user.clientId(), user.organizationId(), id, request));
    }

    /** List all messages in an interaction, ordered by time. */
    @GetMapping("/{id}/messages")
    public ResponseEntity<List<AssistedInteractionMessageResponse>> listMessages(@PathVariable UUID id) {
        ClientAuthenticatedUser user = clientAuthenticatedUserContext.getRequiredUser();
        return ResponseEntity.ok(
                portalInteractionService.listMessages(user.clientId(), user.organizationId(), id));
    }
}
