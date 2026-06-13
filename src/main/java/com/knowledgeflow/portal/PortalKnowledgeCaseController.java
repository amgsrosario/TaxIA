package com.knowledgeflow.portal;

import com.knowledgeflow.cases.dto.KnowledgeCaseDetailResponse;
import com.knowledgeflow.cases.dto.KnowledgeCaseResponse;
import com.knowledgeflow.security.ClientAuthenticatedUser;
import com.knowledgeflow.security.ClientAuthenticatedUserContext;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Client-facing portal endpoints for KnowledgeCases.
 * Requires a CLIENT_PORTAL JWT — enforced by {@link ClientAuthenticatedUserContext#getRequiredUser()}.
 * No {@code @PreAuthorize} roles needed: portal tokens carry no role claims.
 */
@RestController
@RequestMapping("/api/v1/portal/cases")
public class PortalKnowledgeCaseController {

    private final PortalKnowledgeCaseService portalKnowledgeCaseService;
    private final ClientAuthenticatedUserContext clientAuthenticatedUserContext;

    public PortalKnowledgeCaseController(
            PortalKnowledgeCaseService portalKnowledgeCaseService,
            ClientAuthenticatedUserContext clientAuthenticatedUserContext) {
        this.portalKnowledgeCaseService = portalKnowledgeCaseService;
        this.clientAuthenticatedUserContext = clientAuthenticatedUserContext;
    }

    /**
     * List all VALIDATED cases belonging to the authenticated client.
     */
    @GetMapping
    public ResponseEntity<Page<KnowledgeCaseResponse>> list(
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        ClientAuthenticatedUser user = clientAuthenticatedUserContext.getRequiredUser();
        return ResponseEntity.ok(
                portalKnowledgeCaseService.list(user.clientId(), user.organizationId(), pageable));
    }

    /**
     * Get a single VALIDATED case by ID, scoped to the authenticated client.
     */
    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeCaseDetailResponse> get(@PathVariable UUID id) {
        ClientAuthenticatedUser user = clientAuthenticatedUserContext.getRequiredUser();
        return ResponseEntity.ok(
                portalKnowledgeCaseService.get(user.clientId(), user.organizationId(), id));
    }
}
