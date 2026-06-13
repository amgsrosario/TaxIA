package com.knowledgeflow.clients.service;

import com.knowledgeflow.auth.service.JwtService;
import com.knowledgeflow.clients.dto.ClientPortalAuthResponse;
import com.knowledgeflow.clients.dto.ClientPortalLoginRequest;
import com.knowledgeflow.clients.entity.ClientPortalUser;
import com.knowledgeflow.clients.enums.ClientPortalUserStatus;
import com.knowledgeflow.clients.repository.ClientPortalUserRepository;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClientPortalAuthService {

    private final ClientPortalUserRepository clientPortalUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public ClientPortalAuthService(
            ClientPortalUserRepository clientPortalUserRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.clientPortalUserRepository = clientPortalUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public ClientPortalAuthResponse login(ClientPortalLoginRequest request) {
        ClientPortalUser portalUser = clientPortalUserRepository
                .findByOrganizationIdAndEmailIgnoreCaseAndDeletedAtIsNull(
                        request.organizationId(), request.email())
                .orElseThrow(this::invalidCredentials);

        if (portalUser.getStatus() != ClientPortalUserStatus.ACTIVE
                || !passwordEncoder.matches(request.password(), portalUser.getPasswordHash())) {
            throw invalidCredentials();
        }

        return jwtService.issueClientPortalToken(portalUser);
    }

    private BusinessException invalidCredentials() {
        return new BusinessException(ApiErrorCode.UNAUTHORIZED, "Invalid email or password");
    }
}
