package com.knowledgeflow.clients.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.audit.repository.AuditEventRepository;
import com.knowledgeflow.clients.dto.ClientCreateRequest;
import com.knowledgeflow.clients.dto.ClientDetailResponse;
import com.knowledgeflow.clients.enums.ClientStatus;
import com.knowledgeflow.clients.exception.ClientNotFoundException;
import com.knowledgeflow.clients.repository.ClientRepository;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.cases.repository.KnowledgeCaseRepository;
import com.knowledgeflow.cases.repository.KnowledgeCaseVersionRepository;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.organizations.repository.OrganizationUserRepository;
import com.knowledgeflow.users.entity.User;
import com.knowledgeflow.users.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class ClientServiceIntegrationTest {

    @Autowired
    private ClientService clientService;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private KnowledgeCaseVersionRepository knowledgeCaseVersionRepository;

    @Autowired
    private KnowledgeCaseRepository knowledgeCaseRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationUserRepository organizationUserRepository;

    @Autowired
    private UserRepository userRepository;

    private Organization organization;
    private User user;

    @BeforeEach
    void setUp() {
        auditEventRepository.deleteAll();
        knowledgeCaseVersionRepository.deleteAll();
        knowledgeCaseRepository.deleteAll();
        clientRepository.deleteAll();
        organizationUserRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        organization = organizationRepository.save(new Organization("KnowledgeFlow Clients", null));
        user = userRepository.save(new User(
                "author-%s@knowledgeflow.test".formatted(UUID.randomUUID()),
                "Test Author",
                "hash-not-used"
        ));
    }

    @Test
    void createClientStoresClientInsideOrganization() {
        ClientDetailResponse response = clientService.create(organization.getId(), user.getId(), new ClientCreateRequest(
                "Empresa Exemplo",
                "PT123456789",
                "financeiro@example.com",
                "+351 210 000 000",
                "Cliente de teste"
        ));

        assertThat(response.id()).isNotNull();
        assertThat(response.organizationId()).isEqualTo(organization.getId());
        assertThat(response.name()).isEqualTo("Empresa Exemplo");
        assertThat(response.status()).isEqualTo(ClientStatus.ACTIVE);
    }

    @Test
    void createClientRecordsAuditEvent() {
        ClientDetailResponse response = clientService.create(organization.getId(), user.getId(), new ClientCreateRequest(
                "Auditada Lda",
                null,
                null,
                null,
                null
        ));

        var events = auditEventRepository.findByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
                organization.getId(), "Client", response.id());

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getAction().name()).isEqualTo("CLIENT_CREATED");
        assertThat(events.getFirst().getUserId()).isEqualTo(user.getId());
    }

    @Test
    void listOnlyReturnsClientsFromRequestedOrganization() {
        Organization otherOrganization = organizationRepository.save(new Organization("Other Org", null));
        clientService.create(organization.getId(), user.getId(), new ClientCreateRequest(
                "Cliente KnowledgeFlow", null, null, null, null));
        clientService.create(otherOrganization.getId(), user.getId(), new ClientCreateRequest(
                "Cliente Outra Org", null, null, null, null));

        var page = clientService.list(organization.getId(), null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().getFirst().name()).isEqualTo("Cliente KnowledgeFlow");
    }

    @Test
    void duplicateTaxIdentifierInsideOrganizationIsRejected() {
        clientService.create(organization.getId(), user.getId(), new ClientCreateRequest(
                "Primeiro Cliente", "PT999999990", null, null, null));

        assertThatThrownBy(() -> clientService.create(organization.getId(), user.getId(), new ClientCreateRequest(
                "Segundo Cliente", "PT999999990", null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("A client with this tax identifier already exists");
    }

    @Test
    void archiveHidesClientFromActiveLookups() {
        ClientDetailResponse response = clientService.create(organization.getId(), user.getId(), new ClientCreateRequest(
                "Cliente Arquivado", null, null, null, null));

        clientService.archive(organization.getId(), user.getId(), response.id());

        assertThatThrownBy(() -> clientService.get(organization.getId(), response.id()))
                .isInstanceOf(ClientNotFoundException.class);
        assertThat(clientRepository.findById(response.id()).orElseThrow().getStatus()).isEqualTo(ClientStatus.ARCHIVED);
    }

    @Test
    void getRejectsClientFromAnotherOrganization() {
        Organization otherOrganization = organizationRepository.save(new Organization("Other Org", null));
        ClientDetailResponse response = clientService.create(otherOrganization.getId(), user.getId(), new ClientCreateRequest(
                "Cliente Outra Org", null, null, null, null));

        assertThatThrownBy(() -> clientService.get(organization.getId(), response.id()))
                .isInstanceOf(ClientNotFoundException.class);
    }
}
