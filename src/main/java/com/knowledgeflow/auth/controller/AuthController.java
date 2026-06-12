package com.knowledgeflow.auth.controller;

import com.knowledgeflow.auth.dto.AuthResponse;
import com.knowledgeflow.auth.dto.BootstrapAdminRequest;
import com.knowledgeflow.auth.dto.CurrentUserResponse;
import com.knowledgeflow.auth.dto.LoginRequest;
import com.knowledgeflow.auth.service.AuthService;
import com.knowledgeflow.security.AuthenticatedUser;
import com.knowledgeflow.security.AuthenticatedUserContext;
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

    public AuthController(AuthService authService, AuthenticatedUserContext authenticatedUserContext) {
        this.authService = authService;
        this.authenticatedUserContext = authenticatedUserContext;
    }

    @PostMapping("/bootstrap-admin")
    public ResponseEntity<AuthResponse> bootstrapAdmin(@Valid @RequestBody BootstrapAdminRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.bootstrapAdmin(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CurrentUserResponse> me() {
        AuthenticatedUser user = authenticatedUserContext.getRequiredUser();
        return ResponseEntity.ok(new CurrentUserResponse(
                user.userId(),
                user.organizationId(),
                user.email(),
                user.roles()
        ));
    }
}
