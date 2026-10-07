package com.knowledgeflow.users.credentials;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Reason for an ADMIN action on a staff account (revoke sessions, disable, reactivate). */
public record AdminUserActionRequest(
        @NotBlank @Size(max = CredentialAuditMetadata.MAX_REASON_LENGTH) String reason
) {
}
