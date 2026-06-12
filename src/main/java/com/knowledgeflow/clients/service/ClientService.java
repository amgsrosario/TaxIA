package com.knowledgeflow.clients.service;

import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.service.AuditService;
import com.knowledgeflow.clients.dto.ClientCreateRequest;
import com.knowledgeflow.clients.dto.ClientDetailResponse;
import com.knowledgeflow.clients.dto.ClientResponse;
import com.knowledgeflow.clients.dto.ClientUpdateRequest;
import com.knowledgeflow.clients.entity.Client;
import com.knowledgeflow.clients.enums.ClientStatus;
import com.knowledgeflow.clients.exception.ClientNotFoundException;
import com.knowledgeflow.clients.mapper.ClientMapper;
import com.knowledgeflow.clients.repository.ClientRepository;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClientService {

    private static final String ENTITY_TYPE = "Client";

    private final ClientRepository clientRepository;
    private final OrganizationRepository organizationRepository;
    private final ClientMapper clientMapper;
    private final AuditService auditService;

    public ClientService(
            ClientRepository clientRepository,
            OrganizationRepository organizationRepository,
            ClientMapper clientMapper,
            AuditService auditService
    ) {
        this.clientRepository = clientRepository;
        this.organizationRepository = organizationRepository;
        this.clientMapper = clientMapper;
        this.auditService = auditService;
    }

    @Transactional
    public ClientDetailResponse create(UUID organizationId, UUID userId, ClientCreateRequest request) {
        Organization organization = organizationRepository.findByIdAndDeletedAtIsNull(organizationId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.FORBIDDEN, "Organization context is invalid"));
        validateUniqueTaxIdentifier(organizationId, request.taxIdentifier());

        Client client = clientRepository.save(new Client(
                organization,
                request.name(),
                normalizeBlank(request.taxIdentifier()),
                normalizeBlank(request.contactEmail()),
                normalizeBlank(request.phone()),
                normalizeBlank(request.notes())
        ));

        auditService.record(organizationId, userId, AuditAction.CLIENT_CREATED, ENTITY_TYPE, client.getId(),
                "name=" + client.getName());

        return clientMapper.toDetailResponse(client);
    }

    @Transactional(readOnly = true)
    public ClientDetailResponse get(UUID organizationId, UUID clientId) {
        return clientMapper.toDetailResponse(findActiveClient(organizationId, clientId));
    }

    @Transactional(readOnly = true)
    public Page<ClientResponse> list(UUID organizationId, ClientStatus status, Pageable pageable) {
        Page<Client> clients = status == null
                ? clientRepository.findByOrganizationIdAndDeletedAtIsNull(organizationId, pageable)
                : clientRepository.findByOrganizationIdAndStatusAndDeletedAtIsNull(organizationId, status, pageable);

        return clients.map(clientMapper::toResponse);
    }

    @Transactional
    public ClientDetailResponse update(UUID organizationId, UUID userId, UUID clientId, ClientUpdateRequest request) {
        Client client = findActiveClient(organizationId, clientId);
        if (isChanged(client.getTaxIdentifier(), request.taxIdentifier())) {
            validateUniqueTaxIdentifier(organizationId, request.taxIdentifier());
        }

        client.update(
                request.name(),
                normalizeBlank(request.taxIdentifier()),
                normalizeBlank(request.contactEmail()),
                normalizeBlank(request.phone()),
                normalizeBlank(request.notes())
        );

        auditService.record(organizationId, userId, AuditAction.CLIENT_UPDATED, ENTITY_TYPE, client.getId());

        return clientMapper.toDetailResponse(client);
    }

    @Transactional
    public void archive(UUID organizationId, UUID userId, UUID clientId) {
        Client client = findActiveClient(organizationId, clientId);
        client.archive();

        auditService.record(organizationId, userId, AuditAction.CLIENT_ARCHIVED, ENTITY_TYPE, client.getId());
    }

    private Client findActiveClient(UUID organizationId, UUID clientId) {
        return clientRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(clientId, organizationId)
                .orElseThrow(() -> new ClientNotFoundException(clientId));
    }

    private void validateUniqueTaxIdentifier(UUID organizationId, String taxIdentifier) {
        String normalized = normalizeBlank(taxIdentifier);
        if (normalized != null && clientRepository.existsByOrganizationIdAndTaxIdentifierAndDeletedAtIsNull(
                organizationId,
                normalized
        )) {
            throw new BusinessException(ApiErrorCode.CONFLICT, "A client with this tax identifier already exists");
        }
    }

    private boolean isChanged(String currentValue, String newValue) {
        String current = normalizeBlank(currentValue);
        String next = normalizeBlank(newValue);
        return current == null ? next != null : !current.equals(next);
    }

    private String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
