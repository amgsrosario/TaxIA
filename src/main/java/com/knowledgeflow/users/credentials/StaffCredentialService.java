package com.knowledgeflow.users.credentials;

import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.service.AuditService;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.users.entity.User;
import com.knowledgeflow.users.enums.UserStatus;
import com.knowledgeflow.users.repository.UserRowLock;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service staff credential operations (ADR-006): own password change and logout-all.
 *
 * <p>Both lock the user row and re-check, under the lock, that the calling token is still the
 * current one ({@code tv}), so a session revoked concurrently cannot complete the operation.
 * Both increment token_version: the calling token stops working immediately and no new token is
 * issued — a new login is required.
 */
@Service
public class StaffCredentialService {

    private final UserRowLock userRowLock;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public StaffCredentialService(UserRowLock userRowLock, PasswordEncoder passwordEncoder,
                                  AuditService auditService) {
        this.userRowLock = userRowLock;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional
    public void changeOwnPassword(UUID userId, UUID organizationId, long tokenVersion,
                                  String currentPassword, String newPassword) {
        User user = lockCurrentSession(userId, tokenVersion);
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR, "A password actual não está correcta.");
        }
        StaffPasswordPolicy.violation(newPassword, user.getEmail()).ifPresent(message -> {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR, message);
        });
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "A nova password tem de ser diferente da actual.");
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        auditService.record(organizationId, userId, AuditAction.USER_PASSWORD_CHANGED, "User", userId,
                CredentialAuditMetadata.of(userId.toString(), userId, organizationId, null));
    }

    @Transactional
    public void logoutAll(UUID userId, UUID organizationId, long tokenVersion) {
        User user = lockCurrentSession(userId, tokenVersion);
        user.revokeSessions();
        auditService.record(organizationId, userId, AuditAction.USER_SESSIONS_REVOKED, "User", userId,
                CredentialAuditMetadata.of(userId.toString(), userId, organizationId, "logout-all"));
    }

    private User lockCurrentSession(UUID userId, long tokenVersion) {
        User user = userRowLock.lock(userId).orElse(null);
        if (user == null || user.isDeleted() || user.getStatus() != UserStatus.ACTIVE
                || user.getTokenVersion() != tokenVersion) {
            throw new BusinessException(ApiErrorCode.UNAUTHORIZED, "Sessão inválida ou revogada.");
        }
        return user;
    }
}
