package com.knowledgeflow.clients.service;

import com.knowledgeflow.clients.dto.ClientPortalUserCreateRequest;
import com.knowledgeflow.clients.dto.ClientPortalUserResponse;
import com.knowledgeflow.clients.entity.Client;
import com.knowledgeflow.clients.entity.ClientPortalUser;
import com.knowledgeflow.clients.exception.ClientNotFoundException;
import com.knowledgeflow.clients.repository.ClientPortalUserRepository;
import com.knowledgeflow.clients.repository.ClientRepository;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClientPortalUserService {

    private final ClientPortalUserRepository clientPortalUserRepository;
    private final ClientRepository clientRepository;
    private final PasswordEncoder passwordEncoder;

    public ClientPortalUserService(
            ClientPortalUserRepository clientPortalUserRepository,
            ClientRepository clientRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.clientPortalUserRepository = clientPortalUserRepository;
        this.clientRepository = clientRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public ClientPortalUserResponse create(UUID organizationId, UUID clientId, ClientPortalUserCreateRequest request) {
        Client client = clientRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(clientId, organizationId)
                .orElseThrow(() -> new ClientNotFoundException(clientId));

        if (clientPortalUserRepository.existsByClientIdAndEmailAndDeletedAtIsNull(clientId, request.email())) {
            throw new BusinessException(ApiErrorCode.CONFLICT,
                    "A portal user with this email already exists for this client");
        }

        ClientPortalUser portalUser = clientPortalUserRepository.save(
                new ClientPortalUser(client, client.getOrganization(), request.email(),
                        passwordEncoder.encode(request.password())));

        return toResponse(portalUser);
    }

    @Transactional(readOnly = true)
    public List<ClientPortalUserResponse> list(UUID organizationId, UUID clientId) {
        clientRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(clientId, organizationId)
                .orElseThrow(() -> new ClientNotFoundException(clientId));

        return clientPortalUserRepository
                .findByClientIdAndOrganizationIdAndDeletedAtIsNull(clientId, organizationId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public void deactivate(UUID organizationId, UUID clientId, UUID portalUserId) {
        clientRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(clientId, organizationId)
                .orElseThrow(() -> new ClientNotFoundException(clientId));

        ClientPortalUser portalUser = clientPortalUserRepository
                .findByIdAndOrganizationIdAndDeletedAtIsNull(portalUserId, organizationId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND, "Portal user not found"));

        portalUser.deactivate();
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
