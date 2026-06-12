package com.knowledgeflow.interactions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.ai.StubAIService;
import com.knowledgeflow.audit.repository.AuditEventRepository;
import com.knowledgeflow.billing.entity.CommercialPlan;
import com.knowledgeflow.billing.entity.OrganizationPlan;
import com.knowledgeflow.billing.enums.PlanType;
import com.knowledgeflow.billing.repository.CommercialPlanRepository;
import com.knowledgeflow.billing.repository.ConsumptionEventRepository;
import com.knowledgeflow.billing.repository.OrganizationPlanRepository;
import com.knowledgeflow.cases.enums.KnowledgeCaseStatus;
import com.knowledgeflow.cases.repository.KnowledgeCaseCommentRepository;
import com.knowledgeflow.cases.repository.KnowledgeCaseRepository;
import com.knowledgeflow.cases.repository.KnowledgeCaseVersionRepository;
import com.knowledgeflow.clients.dto.ClientCreateRequest;
import com.knowledgeflow.clients.dto.ClientDetailResponse;
import com.knowledgeflow.clients.repository.ClientRepository;
import com.knowledgeflow.clients.service.ClientService;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.interactions.dto.AssistedInteractionAskRequest;
import com.knowledgeflow.interactions.dto.AssistedInteractionCreateRequest;
import com.knowledgeflow.interactions.dto.AssistedInteractionMessageResponse;
import com.knowledgeflow.interactions.dto.AssistedInteractionPromoteRequest;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class AssistedInteractionServiceIntegrationTest {

    @Autowired private AssistedInteractionService service;
    @Autowired private ClientService clientService;

    @Autowired private AssistedInteractionRepository interactionRepository;
    @Autowired private AssistedInteractionMessageRepository messageRepository;
    @Autowired private ConsumptionEventRepository consumptionEventRepository;
    @Autowired private OrganizationPlanRepository organizationPlanRepository;
    @Autowired private CommercialPlanRepository commercialPlanRepository;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private KnowledgeCaseCommentRepository knowledgeCaseCommentRepository;
    @Autowired private KnowledgeCaseVersionRepository knowledgeCaseVersionRepository;
    @Autowired private KnowledgeCaseRepository knowledgeCaseRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private OrganizationUserRepository organizationUserRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;

    private Organization organization;
    private User user;
    private ClientDetailResponse client;

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        interactionRepository.deleteAll();
        consumptionEventRepository.deleteAll();
        organizationPlanRepository.deleteAll();
        commercialPlanRepository.deleteAll();
        auditEventRepository.deleteAll();
        knowledgeCaseCommentRepository.deleteAll();
        knowledgeCaseVersionRepository.deleteAll();
        knowledgeCaseRepository.deleteAll();
        clientRepository.deleteAll();
        organizationUserRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        organization = organizationRepository.save(new Organization("Interaction Test Org", null));
        user = userRepository.save(new User(
                "interaction-%s@knowledgeflow.test".formatted(UUID.randomUUID()),
                "Interaction User",
                "hash-not-used"
        ));

        CommercialPlan plan = commercialPlanRepository.save(
                new CommercialPlan("Unlimited", PlanType.MONTHLY, null, null, null));
        organizationPlanRepository.save(
                new OrganizationPlan(organization.getId(), plan, Instant.now().minusSeconds(60), null));

        client = clientService.create(organization.getId(), user.getId(), new ClientCreateRequest(
                "Cliente Interaction", null, null, null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void createInteractionStoresOpenSession() {
        AssistedInteractionResponse response = service.create(organization.getId(), user.getId(),
                new AssistedInteractionCreateRequest(client.id(), "Consulta de IVA"));

        assertThat(response.id()).isNotNull();
        assertThat(response.status()).isEqualTo(AssistedInteractionStatus.OPEN);
        assertThat(response.clientId()).isEqualTo(client.id());
    }

    @Test
    void askReturnsStubAnswerAndRecordsMessage() {
        AssistedInteractionResponse interaction = service.create(organization.getId(), user.getId(),
                new AssistedInteractionCreateRequest(client.id(), "Consulta fiscal"));

        AssistedInteractionMessageResponse message = service.ask(organization.getId(), user.getId(),
                interaction.id(), new AssistedInteractionAskRequest("Qual a taxa de IVA aplicável?"));

        assertThat(message.answer()).startsWith(StubAIService.STUB_ANSWER_PREFIX);
        assertThat(message.modelUsed()).isEqualTo(StubAIService.STUB_MODEL);
        assertThat(message.question()).isEqualTo("Qual a taxa de IVA aplicável?");
    }

    @Test
    void askRecordsInteractionConsumptionEvent() {
        AssistedInteractionResponse interaction = service.create(organization.getId(), user.getId(),
                new AssistedInteractionCreateRequest(client.id(), "Consulta"));

        service.ask(organization.getId(), user.getId(),
                interaction.id(), new AssistedInteractionAskRequest("Pergunta"));

        assertThat(consumptionEventRepository.count()).isEqualTo(1);
        assertThat(consumptionEventRepository.findAll().getFirst().getEventType().name())
                .isEqualTo("AI_INTERACTION");
    }

    @Test
    void askBlockedWhenInteractionLimitReached() {
        CommercialPlan limitedPlan = commercialPlanRepository.save(
                new CommercialPlan("Limited", PlanType.INTERACTIONS, null, 1, null));
        organizationPlanRepository.deleteAll();
        organizationPlanRepository.save(
                new OrganizationPlan(organization.getId(), limitedPlan, Instant.now().minusSeconds(60), null));

        AssistedInteractionResponse interaction = service.create(organization.getId(), user.getId(),
                new AssistedInteractionCreateRequest(client.id(), "Consulta"));

        service.ask(organization.getId(), user.getId(),
                interaction.id(), new AssistedInteractionAskRequest("Primeira pergunta"));

        assertThatThrownBy(() -> service.ask(organization.getId(), user.getId(),
                interaction.id(), new AssistedInteractionAskRequest("Segunda pergunta — bloqueada")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Interaction limit reached");
    }

    @Test
    void listMessagesReturnsMessagesInOrder() {
        AssistedInteractionResponse interaction = service.create(organization.getId(), user.getId(),
                new AssistedInteractionCreateRequest(client.id(), "Consulta"));

        service.ask(organization.getId(), user.getId(), interaction.id(),
                new AssistedInteractionAskRequest("Pergunta 1"));
        service.ask(organization.getId(), user.getId(), interaction.id(),
                new AssistedInteractionAskRequest("Pergunta 2"));

        var messages = service.listMessages(organization.getId(), interaction.id());
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).question()).isEqualTo("Pergunta 1");
        assertThat(messages.get(1).question()).isEqualTo("Pergunta 2");
    }

    @Test
    void promoteCreatesKnowledgeCaseAndMarksInteractionAsPromoted() {
        AssistedInteractionResponse interaction = service.create(organization.getId(), user.getId(),
                new AssistedInteractionCreateRequest(client.id(), "Consulta fiscal"));

        service.ask(organization.getId(), user.getId(), interaction.id(),
                new AssistedInteractionAskRequest("Qual o regime aplicável?"));

        var knowledgeCase = service.promote(organization.getId(), user.getId(), interaction.id(),
                new AssistedInteractionPromoteRequest(
                        "Enquadramento fiscal — caso formal",
                        "Qual o regime de IVA aplicável a esta operação?",
                        "Rascunho baseado na consulta assistiva."
                ));

        assertThat(knowledgeCase.status()).isEqualTo(KnowledgeCaseStatus.DRAFT);
        assertThat(knowledgeCase.clientId()).isEqualTo(client.id());

        var promotedInteraction = service.get(organization.getId(), interaction.id());
        assertThat(promotedInteraction.status()).isEqualTo(AssistedInteractionStatus.PROMOTED);
        assertThat(promotedInteraction.promotedCaseId()).isEqualTo(knowledgeCase.id());
    }

    @Test
    void cannotPromoteAlreadyPromotedInteraction() {
        AssistedInteractionResponse interaction = service.create(organization.getId(), user.getId(),
                new AssistedInteractionCreateRequest(client.id(), "Consulta"));

        var promoteRequest = new AssistedInteractionPromoteRequest("Título", "Pergunta", null);
        service.promote(organization.getId(), user.getId(), interaction.id(), promoteRequest);

        assertThatThrownBy(() ->
                service.promote(organization.getId(), user.getId(), interaction.id(), promoteRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only OPEN interactions can be promoted");
    }
}
