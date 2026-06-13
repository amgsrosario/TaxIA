package com.knowledgeflow.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.audit.repository.AuditEventRepository;
import com.knowledgeflow.billing.entity.CommercialPlan;
import com.knowledgeflow.billing.entity.OrganizationPlan;
import com.knowledgeflow.billing.enums.PlanType;
import com.knowledgeflow.billing.repository.CommercialPlanRepository;
import com.knowledgeflow.billing.repository.ConsumptionEventRepository;
import com.knowledgeflow.billing.repository.OrganizationPlanRepository;
import com.knowledgeflow.cases.dto.KnowledgeCaseCreateRequest;
import com.knowledgeflow.cases.dto.KnowledgeCaseDetailResponse;
import com.knowledgeflow.cases.dto.KnowledgeCaseResponse;
import com.knowledgeflow.cases.enums.KnowledgeCaseStatus;
import com.knowledgeflow.cases.repository.KnowledgeCaseCommentRepository;
import com.knowledgeflow.cases.repository.KnowledgeCaseRepository;
import com.knowledgeflow.cases.repository.KnowledgeCaseVersionRepository;
import com.knowledgeflow.cases.service.KnowledgeCaseService;
import com.knowledgeflow.clients.dto.ClientCreateRequest;
import com.knowledgeflow.clients.dto.ClientDetailResponse;
import com.knowledgeflow.clients.dto.ClientPortalUserCreateRequest;
import com.knowledgeflow.clients.repository.ClientPortalUserRepository;
import com.knowledgeflow.clients.repository.ClientRepository;
import com.knowledgeflow.clients.service.ClientPortalUserService;
import com.knowledgeflow.clients.service.ClientService;
import com.knowledgeflow.common.error.BusinessException;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class PortalKnowledgeCaseServiceIntegrationTest {

    @Autowired private PortalKnowledgeCaseService portalKnowledgeCaseService;
    @Autowired private KnowledgeCaseService knowledgeCaseService;
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
    private User user;
    private ClientDetailResponse client;
    private UUID portalUserId;

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

        organization = organizationRepository.save(new Organization("Portal Case Test Org", null));
        user = userRepository.save(new User(
                "staff-%s@knowledgeflow.test".formatted(UUID.randomUUID()),
                "Staff User", "hash-not-used"));

        CommercialPlan plan = commercialPlanRepository.save(
                new CommercialPlan("Unlimited", PlanType.MONTHLY, null, null, null));
        organizationPlanRepository.save(
                new OrganizationPlan(organization.getId(), plan, Instant.now().minusSeconds(60), null));

        client = clientService.create(organization.getId(), user.getId(),
                new ClientCreateRequest("Empresa Cliente", null, null, null, null,
                        null, null, null, null, null, null, null, null));

        var portalUser = clientPortalUserService.create(organization.getId(), client.id(),
                new ClientPortalUserCreateRequest("portal@example.com", "senha-123"));
        portalUserId = portalUser.id();
    }

    @Test
    void listReturnsOnlyValidatedCasesForTheAuthenticatedClient() {
        // Create and validate one case
        KnowledgeCaseDetailResponse created = knowledgeCaseService.create(
                organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(client.id(), "Caso Validado", "Pergunta", "Resposta"));
        knowledgeCaseService.submitForReview(organization.getId(), user.getId(), created.id(), null);
        knowledgeCaseService.validate(organization.getId(), user.getId(), created.id(), null);

        // Create a second case in DRAFT state (should not appear in portal)
        knowledgeCaseService.create(organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(client.id(), "Caso Rascunho", "Pergunta", null));

        Page<KnowledgeCaseResponse> page = portalKnowledgeCaseService.list(
                client.id(), organization.getId(), PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().getFirst().title()).isEqualTo("Caso Validado");
        assertThat(page.getContent().getFirst().status()).isEqualTo(KnowledgeCaseStatus.VALIDATED);
    }

    @Test
    void listDoesNotReturnOtherClientsValidatedCases() {
        ClientDetailResponse otherClient = clientService.create(organization.getId(), user.getId(),
                new ClientCreateRequest("Outro Cliente", null, null, null, null,
                        null, null, null, null, null, null, null, null));

        KnowledgeCaseDetailResponse otherCase = knowledgeCaseService.create(
                organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(otherClient.id(), "Caso do Outro", "Pergunta", null));
        knowledgeCaseService.submitForReview(organization.getId(), user.getId(), otherCase.id(), null);
        knowledgeCaseService.validate(organization.getId(), user.getId(), otherCase.id(), null);

        Page<KnowledgeCaseResponse> page = portalKnowledgeCaseService.list(
                client.id(), organization.getId(), PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(0);
    }

    @Test
    void getReturnsValidatedCaseWithFullDetail() {
        KnowledgeCaseDetailResponse created = knowledgeCaseService.create(
                organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(client.id(), "Caso Detalhado", "A pergunta", "O conteúdo"));
        knowledgeCaseService.submitForReview(organization.getId(), user.getId(), created.id(), null);
        knowledgeCaseService.validate(organization.getId(), user.getId(), created.id(), null);

        KnowledgeCaseDetailResponse detail = portalKnowledgeCaseService.get(
                client.id(), organization.getId(), created.id());

        assertThat(detail.id()).isEqualTo(created.id());
        assertThat(detail.title()).isEqualTo("Caso Detalhado");
        assertThat(detail.question()).isEqualTo("A pergunta");
        assertThat(detail.content()).isEqualTo("O conteúdo");
        assertThat(detail.status()).isEqualTo(KnowledgeCaseStatus.VALIDATED);
        assertThat(detail.clientId()).isEqualTo(client.id());
    }

    @Test
    void getThrowsForNonValidatedCase() {
        KnowledgeCaseDetailResponse draft = knowledgeCaseService.create(
                organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(client.id(), "Rascunho", "Pergunta", null));

        assertThatThrownBy(() ->
                portalKnowledgeCaseService.get(client.id(), organization.getId(), draft.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Case not found");
    }

    @Test
    void getThrowsForCaseBelongingToAnotherClient() {
        ClientDetailResponse otherClient = clientService.create(organization.getId(), user.getId(),
                new ClientCreateRequest("Outro", null, null, null, null,
                        null, null, null, null, null, null, null, null));
        KnowledgeCaseDetailResponse otherCase = knowledgeCaseService.create(
                organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(otherClient.id(), "Outro Caso", "Pergunta", null));
        knowledgeCaseService.submitForReview(organization.getId(), user.getId(), otherCase.id(), null);
        knowledgeCaseService.validate(organization.getId(), user.getId(), otherCase.id(), null);

        assertThatThrownBy(() ->
                portalKnowledgeCaseService.get(client.id(), organization.getId(), otherCase.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Case not found");
    }
}
