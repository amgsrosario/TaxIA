package com.knowledgeflow.auth.controller;

import com.knowledgeflow.auth.dto.AuthResponse;
import com.knowledgeflow.auth.dto.BootstrapAdminRequest;
import com.knowledgeflow.auth.dto.CurrentUserResponse;
import com.knowledgeflow.auth.dto.LoginRequest;
import com.knowledgeflow.auth.service.AuthService;
import com.knowledgeflow.security.AuthenticatedUser;
import com.knowledgeflow.security.AuthenticatedUserContext;
import com.knowledgeflow.users.credentials.ChangeOwnPasswordRequest;
import com.knowledgeflow.users.credentials.StaffCredentialService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthenticatedUserContext authenticatedUserContext;
    private final StaffCredentialService staffCredentialService;

    public AuthController(AuthService authService, AuthenticatedUserContext authenticatedUserContext,
                          StaffCredentialService staffCredentialService) {
        this.authService = authService;
        this.authenticatedUserContext = authenticatedUserContext;
        this.staffCredentialService = staffCredentialService;
    }

    @PostMapping("/bootstrap-admin")
    public ResponseEntity<AuthResponse> bootstrapAdmin(
            @Valid @RequestBody BootstrapAdminRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-Bootstrap-Secret", required = false)
            String bootstrapSecret) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(authService.bootstrapAdmin(request, bootstrapSecret));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()") // the staff-only boundary is enforced in SecurityConfig
    public ResponseEntity<CurrentUserResponse> me() {
        AuthenticatedUser user = authenticatedUserContext.getRequiredUser();
        return ResponseEntity.ok(new CurrentUserResponse(
                user.userId(),
                user.organizationId(),
                user.email(),
                user.roles(),
                authenticatedUserContext.isPasswordChangeRequired()
        ));
    }

    /**
     * Own password change (ADR-006). Allowed also while a password change is required. Ends every
     * session of the user, the calling one included: 204 and a new login is required.
     */
    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangeOwnPasswordRequest request) {
        AuthenticatedUser user = authenticatedUserContext.getRequiredUser();
        staffCredentialService.changeOwnPassword(user.userId(), user.organizationId(),
                authenticatedUserContext.requiredTokenVersion(),
                request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    /** Ends every session of the calling user (token_version++), the calling one included. */
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll() {
        AuthenticatedUser user = authenticatedUserContext.getRequiredUser();
        staffCredentialService.logoutAll(user.userId(), user.organizationId(),
                authenticatedUserContext.requiredTokenVersion());
        return ResponseEntity.noContent().build();
    }
}
