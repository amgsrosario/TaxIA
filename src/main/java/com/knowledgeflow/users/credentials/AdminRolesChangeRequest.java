package com.knowledgeflow.users.credentials;

import com.knowledgeflow.users.enums.RoleName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Set;

/** PUT /api/v1/admin/users/{id}/roles — the complete set of roles in the ADMIN's organization. */
public record AdminRolesChangeRequest(
        @NotEmpty Set<RoleName> roles,
        @NotBlank @Size(max = CredentialAuditMetadata.MAX_REASON_LENGTH) String reason
) {
}
