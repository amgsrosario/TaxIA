package com.knowledgeflow.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.billing.entity.CommercialPlan;
import com.knowledgeflow.billing.entity.OrganizationPlan;
import com.knowledgeflow.billing.enums.PlanType;
import com.knowledgeflow.billing.repository.CommercialPlanRepository;
import com.knowledgeflow.billing.repository.ConsumptionEventRepository;
import com.knowledgeflow.billing.repository.OrganizationPlanRepository;
import com.knowledgeflow.clients.dto.ClientCreateRequest;
import com.knowledgeflow.clients.dto.ClientDetailResponse;
import com.knowledgeflow.clients.repository.ClientRepository;
import com.knowledgeflow.clients.service.ClientService;
import com.knowledgeflow.audit.repository.AuditEventRepository;
import com.knowledgeflow.cases.dto.KnowledgeCaseCreateRequest;
import com.knowledgeflow.cases.repository.KnowledgeCaseCommentRepository;
import com.knowledgeflow.cases.repository.KnowledgeCaseRepository;
import com.knowledgeflow.cases.repository.KnowledgeCaseVersionRepository;
import com.knowledgeflow.cases.service.KnowledgeCaseService;
import com.knowledgeflow.common.error.BusinessException;
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
class EntitlementServiceIntegrationTest {

    @Autowired private EntitlementService entitlementService;
    @Autowired private KnowledgeCaseService knowledgeCaseService;
    @Autowired private ClientService clientService;

    @Autowired private CommercialPlanRepository commercialPlanRepository;
    @Autowired private OrganizationPlanRepository organizationPlanRepository;
    @Autowired private ConsumptionEventRepository consumptionEventRepository;
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

        organization = organizationRepository.save(new Organization("Billing Test Org", null));
        user = userRepository.save(new User(
                "billing-%s@knowledgeflow.test".formatted(UUID.randomUUID()),
                "Billing User",
                "hash-not-used"
        ));
    }

    // -------------------------------------------------------------------------

    @Test
    void organizationWithoutPlanCannotCreateCase() {
        // No plan assigned — any attempt should be blocked
        client = createClient();

        assertThatThrownBy(() -> knowledgeCaseService.create(organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(client.id(), "Parecer", "Pergunta", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No active commercial plan");
    }

    @Test
    void organizationWithUnlimitedPlanCanAlwaysCreateCases() {
        assignPlan(PlanType.MONTHLY, null, null);
        client = createClient();

        // Create multiple cases — should never be blocked
        knowledgeCaseService.create(organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(client.id(), "Parecer 1", "Pergunta", null));
        knowledgeCaseService.create(organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(client.id(), "Parecer 2", "Pergunta", null));

        assertThat(consumptionEventRepository.count()).isEqualTo(2);
    }

    @Test
    void organizationReachingCaseLimitIsBlocked() {
        assignPlan(PlanType.TICKET, 2, null); // max 2 cases
        client = createClient();

        knowledgeCaseService.create(organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(client.id(), "Parecer 1", "Pergunta", null));
        knowledgeCaseService.create(organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(client.id(), "Parecer 2", "Pergunta", null));

        assertThatThrownBy(() -> knowledgeCaseService.create(organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(client.id(), "Parecer 3", "Pergunta", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Case limit reached");
    }

    @Test
    void expiredPlanBlocksNewCases() {
        // Plan ended 1 second ago
        CommercialPlan plan = commercialPlanRepository.save(
                new CommercialPlan("Expired Plan", PlanType.MONTHLY, null, null, null));
        organizationPlanRepository.save(new OrganizationPlan(
                organization.getId(), plan,
                Instant.now().minusSeconds(120),
                Instant.now().minusSeconds(1)   // already expired
        ));
        client = createClient();

        assertThatThrownBy(() -> knowledgeCaseService.create(organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(client.id(), "Parecer", "Pergunta", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No active commercial plan");
    }

    @Test
    void consumptionEventIsRecordedOnCaseCreation() {
        assignPlan(PlanType.MONTHLY, null, null);
        client = createClient();

        knowledgeCaseService.create(organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(client.id(), "Parecer", "Pergunta", null));

        assertThat(consumptionEventRepository.count()).isEqualTo(1);
        assertThat(consumptionEventRepository.findAll().getFirst().getEventType().name())
                .isEqualTo("KNOWLEDGE_CASE_CREATED");
    }

    // -------------------------------------------------------------------------

    private void assignPlan(PlanType type, Integer maxCases, Integer maxInteractions) {
        CommercialPlan plan = commercialPlanRepository.save(
                new CommercialPlan("Test Plan", type, maxCases, maxInteractions, null));
        organizationPlanRepository.save(new OrganizationPlan(
                organization.getId(), plan, Instant.now().minusSeconds(60), null));
    }

    private ClientDetailResponse createClient() {
        return clientService.create(organization.getId(), user.getId(), new ClientCreateRequest(
                "Cliente Billing", null, null, null, null, null, null, null, null, null, null, null, null));
    }
}
