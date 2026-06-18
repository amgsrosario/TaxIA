package com.knowledgeflow.cases.controller;

import com.knowledgeflow.cases.dto.KnowledgeCaseCommentResponse;
import com.knowledgeflow.cases.dto.KnowledgeCaseCreateRequest;
import com.knowledgeflow.cases.dto.KnowledgeCaseDetailResponse;
import com.knowledgeflow.cases.dto.KnowledgeCaseResponse;
import com.knowledgeflow.cases.dto.KnowledgeCaseUpdateRequest;
import com.knowledgeflow.cases.dto.KnowledgeCaseVersionResponse;
import com.knowledgeflow.cases.dto.KnowledgeCaseWorkflowRequest;
import com.knowledgeflow.cases.enums.KnowledgeCaseStatus;
import com.knowledgeflow.cases.service.KnowledgeCaseService;
import com.knowledgeflow.security.AuthenticatedUser;
import com.knowledgeflow.security.AuthenticatedUserContext;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge-cases")
public class KnowledgeCaseController {

    private final KnowledgeCaseService knowledgeCaseService;
    private final AuthenticatedUserContext authenticatedUserContext;

    public KnowledgeCaseController(KnowledgeCaseService knowledgeCaseService, AuthenticatedUserContext authenticatedUserContext) {
        this.knowledgeCaseService = knowledgeCaseService;
        this.authenticatedUserContext = authenticatedUserContext;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR')")
    public ResponseEntity<KnowledgeCaseDetailResponse> create(@Valid @RequestBody KnowledgeCaseCreateRequest request) {
        AuthenticatedUser user = authenticatedUserContext.getRequiredUser();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(knowledgeCaseService.create(user.organizationId(), user.userId(), request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR', 'REVIEWER', 'VALIDATOR', 'VIEWER')")
    public ResponseEntity<KnowledgeCaseDetailResponse> get(@PathVariable UUID id) {
        UUID organizationId = authenticatedUserContext.getRequiredUser().organizationId();
        return ResponseEntity.ok(knowledgeCaseService.get(organizationId, id));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR', 'REVIEWER', 'VALIDATOR', 'VIEWER')")
    public ResponseEntity<Page<KnowledgeCaseResponse>> list(
            @RequestParam(required = false) KnowledgeCaseStatus status,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable
    ) {
        UUID organizationId = authenticatedUserContext.getRequiredUser().organizationId();
        return ResponseEntity.ok(knowledgeCaseService.list(organizationId, status, pageable));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR')")
    public ResponseEntity<KnowledgeCaseDetailResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody KnowledgeCaseUpdateRequest request
    ) {
        AuthenticatedUser user = authenticatedUserContext.getRequiredUser();
        return ResponseEntity.ok(knowledgeCaseService.update(user.organizationId(), user.userId(), id, request));
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR', 'REVIEWER', 'VALIDATOR', 'VIEWER')")
    public ResponseEntity<List<KnowledgeCaseVersionResponse>> listVersions(@PathVariable UUID id) {
        UUID organizationId = authenticatedUserContext.getRequiredUser().organizationId();
        return ResponseEntity.ok(knowledgeCaseService.listVersions(organizationId, id));
    }

    // -------------------------------------------------------------------------
    // Workflow transitions
    // -------------------------------------------------------------------------

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR')")
    public ResponseEntity<KnowledgeCaseDetailResponse> submitForReview(
            @PathVariable UUID id,
            @RequestBody(required = false) KnowledgeCaseWorkflowRequest request
    ) {
        AuthenticatedUser user = authenticatedUserContext.getRequiredUser();
        return ResponseEntity.ok(knowledgeCaseService.submitForReview(user.organizationId(), user.userId(), id, request));
    }

    @PostMapping("/{id}/request-changes")
    @PreAuthorize("hasAnyRole('ADMIN', 'REVIEWER')")
    public ResponseEntity<KnowledgeCaseDetailResponse> requestChanges(
            @PathVariable UUID id,
            @RequestBody(required = false) KnowledgeCaseWorkflowRequest request
    ) {
        AuthenticatedUser user = authenticatedUserContext.getRequiredUser();
        return ResponseEntity.ok(knowledgeCaseService.requestChanges(user.organizationId(), user.userId(), id, request));
    }

    @PostMapping("/{id}/validate")
    @PreAuthorize("hasAnyRole('ADMIN', 'VALIDATOR')")
    public ResponseEntity<KnowledgeCaseDetailResponse> validate(
            @PathVariable UUID id,
            @RequestBody(required = false) KnowledgeCaseWorkflowRequest request
    ) {
        AuthenticatedUser user = authenticatedUserContext.getRequiredUser();
        return ResponseEntity.ok(knowledgeCaseService.validate(user.organizationId(), user.userId(), id, request));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'VALIDATOR')")
    public ResponseEntity<KnowledgeCaseDetailResponse> reject(
            @PathVariable UUID id,
            @RequestBody(required = false) KnowledgeCaseWorkflowRequest request
    ) {
        AuthenticatedUser user = authenticatedUserContext.getRequiredUser();
        return ResponseEntity.ok(knowledgeCaseService.reject(user.organizationId(), user.userId(), id, request));
    }

    @GetMapping("/{id}/comments")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUTHOR', 'REVIEWER', 'VALIDATOR', 'VIEWER')")
    public ResponseEntity<List<KnowledgeCaseCommentResponse>> listComments(@PathVariable UUID id) {
        UUID organizationId = authenticatedUserContext.getRequiredUser().organizationId();
        return ResponseEntity.ok(knowledgeCaseService.listComments(organizationId, id));
    }

    @PostMapping("/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<java.util.Map<String, Object>> reindex() {
        UUID organizationId = authenticatedUserContext.getRequiredUser().organizationId();
        int indexed = knowledgeCaseService.reindexValidatedCases(organizationId);
        return ResponseEntity.ok(java.util.Map.of("indexed", indexed));
    }
}
