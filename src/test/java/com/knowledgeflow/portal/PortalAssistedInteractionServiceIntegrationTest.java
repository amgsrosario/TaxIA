package com.knowledgeflow.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.ai.provider.stub.StubAIProvider;
import com.knowledgeflow.audit.repository.AuditEventRepository;
import com.knowledgeflow.billing.entity.CommercialPlan;
import com.knowledgeflow.billing.entity.OrganizationPlan;
import com.knowledgeflow.billing.enums.PlanType;
import com.knowledgeflow.billing.repository.CommercialPlanRepository;
import com.knowledgeflow.billing.repository.ConsumptionEventRepository;
import com.knowledgeflow.billing.repository.OrganizationPlanRepository;
import com.knowledgeflow.cases.repository.KnowledgeCaseCommentRepository;
import com.knowledgeflow.cases.repository.KnowledgeCaseRepository;
import com.knowledgeflow.cases.repository.KnowledgeCaseVersionRepository;
import com.knowledgeflow.clients.dto.ClientCreateRequest;
import com.knowledgeflow.clients.dto.ClientDetailResponse;
import com.knowledgeflow.clients.dto.ClientPortalUserCreateRequest;
import com.knowledgeflow.clients.repository.ClientPortalUserRepository;
import com.knowledgeflow.clients.repository.ClientRepository;
import com.knowledgeflow.clients.service.ClientPortalUserService;
import com.knowledgeflow.clients.service.ClientService;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.interactions.dto.AssistedInteractionAskRequest;
import com.knowledgeflow.interactions.dto.AssistedInteractionMessageResponse;
import com.knowledgeflow.interactions.dto.AssistedInteractionResponse;
import com.knowledgeflow.interactions.enums.AssistedInteractionStatus;
import com.knowledgeflow.interactions.repository.AssistedInteractionMessageRepository;
import com.knowledgeflow.interactions.repository.AssistedInteractionRepository;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.organizations.repository.OrganizationUserRepository;
import com.knowledgeflow.users.entity.User;
import com.knowledgeflow.users.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class PortalAssistedInteractionServiceIntegrationTest {

    @Autowired private PortalAssistedInteractionService portalInteractionService;
    @Autowired private ClientService clientService;
    @Autowired private ClientPortalUserService clientPortalUserService;

    @Autowired private AssistedInteractionMessageRepository interactionMessageRepository;
    @Autowired private AssistedInteractionRepository interactionRepository;
    @Autowired private ConsumptionEventRepository consumptionEventRepository;
    @Autowired private OrganizationPlanRepository organizationPlanRepository;
    @Autowired private CommercialPlanRepository commercialPlanRepository;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private KnowledgeCaseCommentRepository knowledgeCaseCommentRepository;
    @Autowired private KnowledgeCaseVersionRepository knowledgeCaseVersionRepository;
    @Autowired private KnowledgeCaseRepository knowledgeCaseRepository;
    @Autowired private ClientPortalUserRepository clientPortalUserRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private OrganizationUserRepository organizationUserRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;

    private Organization organization;
    private ClientDetailResponse client;

    @BeforeEach
    void setUp() {
        interactionMessageRepository.deleteAll();
        interactionRepository.deleteAll();
        consumptionEventRepository.deleteAll();
        organizationPlanRepository.deleteAll();
        commercialPlanRepository.deleteAll();
        auditEventRepository.deleteAll();
        knowledgeCaseCommentRepository.deleteAll();
        knowledgeCaseVersionRepository.deleteAll();
        knowledgeCaseRepository.deleteAll();
        clientPortalUserRepository.deleteAll();
        clientRepository.deleteAll();
        organizationUserRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        organization = organizationRepository.save(new Organization("Portal Interaction Test Org", null));
        User user = userRepository.save(new User(
                "staff-%s@knowledgeflow.test".formatted(UUID.randomUUID()),
                "Staff", "hash-not-used"));

        CommercialPlan plan = commercialPlanRepository.save(
                new CommercialPlan("Unlimited", PlanType.MONTHLY, null, null, null));
        organizationPlanRepository.save(
                new OrganizationPlan(organization.getId(), plan, Instant.now().minusSeconds(60), null));

        client = clientService.create(organization.getId(), user.getId(),
                new ClientCreateRequest("Empresa Cliente", null, null, null, null,
                        null, null, null, null, null, null, null, null));

        clientPortalUserService.create(organization.getId(), client.id(),
                new ClientPortalUserCreateRequest("portal@example.com", "senha-123"));
    }

    @Test
    void createInteractionFromPortalSetsStatusOpen() {
        AssistedInteractionResponse response = portalInteractionService.create(
                client.id(), organization.getId(), "Dúvida sobre faturação");

        assertThat(response.id()).isNotNull();
        assertThat(response.status()).isEqualTo(AssistedInteractionStatus.OPEN);
        assertThat(response.title()).isEqualTo("Dúvida sobre faturação");
        assertThat(response.clientId()).isEqualTo(client.id());
        assertThat(response.createdByUserId()).isNull(); // no org user for portal interactions
    }

    @Test
    void askReturnsStubAnswerAndRecordsConsumptionEvent() {
        AssistedInteractionResponse interaction = portalInteractionService.create(
                client.id(), organization.getId(), "Sessão de apoio");

        AssistedInteractionMessageResponse message = portalInteractionService.ask(
                client.id(), organization.getId(), interaction.id(),
                new AssistedInteractionAskRequest("Qual é o prazo de entrega?"));

        assertThat(message.question()).isEqualTo("Qual é o prazo de entrega?");
        assertThat(message.answer()).startsWith(StubAIProvider.STUB_ANSWER_PREFIX);
        assertThat(message.modelUsed()).isEqualTo(StubAIProvider.STUB_MODEL);

        long events = consumptionEventRepository.count();
        assertThat(events).isEqualTo(1);
    }

    @Test
    void listReturnsInteractionsForAuthenticatedClientOnly() {
        portalInteractionService.create(client.id(), organization.getId(), "Sessão A");
        portalInteractionService.create(client.id(), organization.getId(), "Sessão B");

        // Another client in the same org — their interaction should not appear
        User anotherUser = userRepository.save(new User(
                "other-%s@knowledgeflow.test".formatted(UUID.randomUUID()), "Other", "hash"));
        ClientDetailResponse otherClient = clientService.create(organization.getId(), anotherUser.getId(),
                new ClientCreateRequest("Outro", null, null, null, null,
                        null, null, null, null, null, null, null, null));
        portalInteractionService.create(otherClient.id(), organization.getId(), "Sessão Outro");

        Page<AssistedInteractionResponse> page = portalInteractionService.list(
                client.id(), organization.getId(), PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).allMatch(r -> r.clientId().equals(client.id()));
    }

    @Test
    void listMessagesReturnsMessagesOrderedByTime() {
        AssistedInteractionResponse interaction = portalInteractionService.create(
                client.id(), organization.getId(), "Sessão");

        portalInteractionService.ask(client.id(), organization.getId(), interaction.id(),
                new AssistedInteractionAskRequest("Pergunta 1"));
        portalInteractionService.ask(client.id(), organization.getId(), interaction.id(),
                new AssistedInteractionAskRequest("Pergunta 2"));

        List<AssistedInteractionMessageResponse> messages = portalInteractionService.listMessages(
                client.id(), organization.getId(), interaction.id());

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).question()).isEqualTo("Pergunta 1");
        assertThat(messages.get(1).question()).isEqualTo("Pergunta 2");
    }

    @Test
    void askOnInteractionBelongingToAnotherClientIsRejected() {
        // Another client creates an interaction
        User anotherUser = userRepository.save(new User(
                "other-%s@knowledgeflow.test".formatted(UUID.randomUUID()), "Other", "hash"));
        ClientDetailResponse otherClient = clientService.create(organization.getId(), anotherUser.getId(),
                new ClientCreateRequest("Outro", null, null, null, null,
                        null, null, null, null, null, null, null, null));
        AssistedInteractionResponse otherInteraction = portalInteractionService.create(
                otherClient.id(), organization.getId(), "Sessão do outro");

        // Authenticated client tries to ask on the other client's interaction
        assertThatThrownBy(() -> portalInteractionService.ask(
                client.id(), organization.getId(), otherInteraction.id(),
                new AssistedInteractionAskRequest("Pergunta indevida")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Interaction not found");
    }
}
