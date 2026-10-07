package com.knowledgeflow.users.credentials;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** POST /api/v1/auth/password. Policy checks happen in the service; toString never shows values. */
public record ChangeOwnPasswordRequest(
        @NotBlank String currentPassword,
        @NotNull String newPassword
) {
    @Override
    public String toString() {
        return "ChangeOwnPasswordRequest[redacted]";
    }
}
