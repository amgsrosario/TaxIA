package com.knowledgeflow.users.credentials;

import com.knowledgeflow.users.enums.UserStatus;
import java.util.List;
import java.util.UUID;

/** Staff account as seen by an ADMIN of the same organization. Never a hash or token. */
public record StaffUserSummary(
        UUID id,
        String email,
        String fullName,
        UserStatus status,
        List<String> roles,
        boolean mustChangePassword,
        boolean self
) {
}
