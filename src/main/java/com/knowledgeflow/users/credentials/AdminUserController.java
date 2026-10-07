package com.knowledgeflow.users.credentials;

import com.knowledgeflow.security.AuthenticatedUserContext;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN administration of staff accounts of the ADMIN's own organization (ADR-006). Every action
 * requires a reason, is audited and returns 204; none returns a password, hash or token.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final StaffAccountAdminService adminService;
    private final AuthenticatedUserContext authenticatedUserContext;

    public AdminUserController(StaffAccountAdminService adminService,
                               AuthenticatedUserContext authenticatedUserContext) {
        this.adminService = adminService;
        this.authenticatedUserContext = authenticatedUserContext;
    }

    @GetMapping
    public List<StaffUserSummary> list() {
        return adminService.listUsers(authenticatedUserContext.getRequiredUser());
    }

    @PostMapping("/{id}/sessions/revoke")
    public ResponseEntity<Void> revokeSessions(@PathVariable UUID id,
                                               @Valid @RequestBody AdminUserActionRequest request) {
        adminService.revokeSessions(authenticatedUserContext.getRequiredUser(), tv(), id, request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/password-reset")
    public ResponseEntity<Void> resetPassword(@PathVariable UUID id,
                                              @Valid @RequestBody AdminPasswordResetRequest request) {
        adminService.resetPassword(authenticatedUserContext.getRequiredUser(), tv(), id,
                request.temporaryPassword(), request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<Void> disable(@PathVariable UUID id, @Valid @RequestBody AdminUserActionRequest request) {
        adminService.disable(authenticatedUserContext.getRequiredUser(), tv(), id, request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reactivate")
    public ResponseEntity<Void> reactivate(@PathVariable UUID id,
                                           @Valid @RequestBody AdminUserActionRequest request) {
        adminService.reactivate(authenticatedUserContext.getRequiredUser(), tv(), id, request.reason());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/roles")
    public ResponseEntity<Void> changeRoles(@PathVariable UUID id,
                                            @Valid @RequestBody AdminRolesChangeRequest request) {
        adminService.changeRoles(authenticatedUserContext.getRequiredUser(), tv(), id, request.roles(), request.reason());
        return ResponseEntity.noContent().build();
    }

    private long tv() {
        return authenticatedUserContext.requiredTokenVersion();
    }
}
