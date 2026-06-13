package com.knowledgeflow.clients.controller;

import com.knowledgeflow.clients.dto.ClientPortalAuthResponse;
import com.knowledgeflow.clients.dto.ClientPortalLoginRequest;
import com.knowledgeflow.clients.service.ClientPortalAuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/client-auth")
public class ClientPortalAuthController {

    private final ClientPortalAuthService clientPortalAuthService;

    public ClientPortalAuthController(ClientPortalAuthService clientPortalAuthService) {
        this.clientPortalAuthService = clientPortalAuthService;
    }

    @PostMapping("/login")
    public ResponseEntity<ClientPortalAuthResponse> login(
            @Valid @RequestBody ClientPortalLoginRequest request) {
        return ResponseEntity.ok(clientPortalAuthService.login(request));
    }
}
