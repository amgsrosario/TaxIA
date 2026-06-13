package com.knowledgeflow.portal;

import com.knowledgeflow.clients.dto.ClientPortalUserResponse;
import com.knowledgeflow.clients.entity.ClientPortalUser;
import com.knowledgeflow.clients.repository.ClientPortalUserRepository;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Portal-facing profile service.
 * Scoped entirely to the authenticated portal user (portalUserId + organizationId from JWT).
 */
@Service
public class PortalProfileService {

    private final ClientPortalUserRepository clientPortalUserRepository;
    private final PasswordEncoder passwordEncoder;

    public PortalProfileService(ClientPortalUserRepository clientPortalUserRepository,
                                 PasswordEncoder passwordEncoder) {
        this.clientPortalUserRepository = clientPortalUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public ClientPortalUserResponse getProfile(UUID portalUserId, UUID organizationId) {
        ClientPortalUser user = findPortalUser(portalUserId, organizationId);
        return toResponse(user);
    }

    @Transactional
    public ClientPortalUserResponse changePassword(UUID portalUserId, UUID organizationId,
                                                    PortalChangePasswordRequest request) {
        ClientPortalUser user = findPortalUser(portalUserId, organizationId);

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException(ApiErrorCode.FORBIDDEN, "Current password is incorrect");
        }

        user.changePassword(passwordEncoder.encode(request.newPassword()));
        return toResponse(clientPortalUserRepository.save(user));
    }

    // -------------------------------------------------------------------------

    private ClientPortalUser findPortalUser(UUID portalUserId, UUID organizationId) {
        return clientPortalUserRepository
                .findByIdAndOrganizationIdAndDeletedAtIsNull(portalUserId, organizationId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND,
                        "Portal user not found: " + portalUserId));
    }

    private ClientPortalUserResponse toResponse(ClientPortalUser u) {
        return new ClientPortalUserResponse(
                u.getId(),
                u.getClient().getId(),
                u.getOrganization().getId(),
                u.getEmail(),
                u.getStatus(),
                u.getCreatedAt(),
                u.getUpdatedAt()
        );
    }
}
