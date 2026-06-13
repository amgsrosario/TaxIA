package com.knowledgeflow.portal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PortalChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 8) String newPassword
) {}
