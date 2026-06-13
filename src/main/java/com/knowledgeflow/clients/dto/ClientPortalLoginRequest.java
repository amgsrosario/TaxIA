package com.knowledgeflow.clients.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ClientPortalLoginRequest(
        @NotNull UUID organizationId,
        @NotBlank @Email String email,
        @NotBlank String password
) {}
