package com.knowledgeflow.rag;

import com.knowledgeflow.rag.dto.RagBatchIndexResultResponse;
import com.knowledgeflow.rag.dto.RagIndexResultResponse;
import com.knowledgeflow.security.AuthenticatedUserContext;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/rag/index/knowledge-cases")
public class RagAdminController {

    private final RagAdminService ragAdminService;
    private final AuthenticatedUserContext authenticatedUserContext;

    public RagAdminController(RagAdminService ragAdminService, AuthenticatedUserContext authenticatedUserContext) {
        this.ragAdminService = ragAdminService;
        this.authenticatedUserContext = authenticatedUserContext;
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RagIndexResultResponse> indexById(@PathVariable UUID id) {
        UUID organizationId = authenticatedUserContext.getRequiredUser().organizationId();
        return ResponseEntity.ok(ragAdminService.indexById(organizationId, id));
    }

    @PostMapping("/validated")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RagBatchIndexResultResponse> indexAllValidated() {
        UUID organizationId = authenticatedUserContext.getRequiredUser().organizationId();
        return ResponseEntity.ok(ragAdminService.indexAllValidated(organizationId));
    }
}
