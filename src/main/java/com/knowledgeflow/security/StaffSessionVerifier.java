package com.knowledgeflow.security;

import com.knowledgeflow.organizations.entity.OrganizationUser;
import com.knowledgeflow.organizations.repository.OrganizationUserRepository;
import com.knowledgeflow.users.entity.User;
import com.knowledgeflow.users.enums.UserStatus;
import com.knowledgeflow.users.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-request database check of a staff token (ADR-006): the user must exist, be ACTIVE and not
 * deleted, hold at least one active membership in the token's organization (organization not
 * deleted), and the token's {@code tv} must equal the current {@code users.token_version}. The
 * roles returned are the ones in the database now — the token's {@code roles} claim is ignored.
 */
@Service
public class StaffSessionVerifier {

    private final UserRepository userRepository;
    private final OrganizationUserRepository organizationUserRepository;

    public StaffSessionVerifier(UserRepository userRepository,
                                OrganizationUserRepository organizationUserRepository) {
        this.userRepository = userRepository;
        this.organizationUserRepository = organizationUserRepository;
    }

    /** Current session state, or empty when the token must be rejected. */
    @Transactional(readOnly = true)
    public Optional<VerifiedStaffSession> verify(UUID userId, UUID organizationId, long tokenVersion) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.isDeleted() || user.getStatus() != UserStatus.ACTIVE) {
            return Optional.empty();
        }
        if (user.getTokenVersion() != tokenVersion) {
            return Optional.empty();
        }
        List<String> roles = organizationUserRepository.findByUserIdAndDeletedAtIsNull(userId).stream()
                .filter(m -> m.getOrganization().getId().equals(organizationId))
                .filter(m -> m.getOrganization().getDeletedAt() == null)
                .map(OrganizationUser::getRole)
                .map(role -> role.getName().name())
                .distinct()
                .sorted()
                .toList();
        if (roles.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new VerifiedStaffSession(roles, user.isMustChangePassword()));
    }

    public record VerifiedStaffSession(List<String> roles, boolean mustChangePassword) {
    }
}
