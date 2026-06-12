package com.knowledgeflow.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BootstrapAdminRequest(
        @NotBlank @Size(max = 160) String organizationName,
        @Size(max = 64) String taxIdentifier,
        @Email @NotBlank String adminEmail,
        @NotBlank @Size(max = 160) String adminFullName,
        @NotBlank @Size(min = 8, max = 128) String adminPassword
) {
}
