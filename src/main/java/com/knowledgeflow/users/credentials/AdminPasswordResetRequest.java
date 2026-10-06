package com.knowledgeflow.users.credentials;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/admin/users/{id}/password-reset. The temporary password is chosen by the ADMIN and
 * handed over out of band; the user must replace it at the next login. toString never shows it.
 */
public record AdminPasswordResetRequest(
        @NotNull String temporaryPassword,
        @NotBlank @Size(max = CredentialAuditMetadata.MAX_REASON_LENGTH) String reason
) {
    @Override
    public String toString() {
        return "AdminPasswordResetRequest[redacted]";
    }
}
