package com.knowledgeflow.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.billing.dto.AssignPlanRequest;
import com.knowledgeflow.billing.dto.CommercialPlanCreateRequest;
import com.knowledgeflow.billing.dto.CommercialPlanResponse;
import com.knowledgeflow.billing.dto.ConsumptionSummaryResponse;
import com.knowledgeflow.billing.dto.OrganizationPlanResponse;
import com.knowledgeflow.billing.enums.PlanType;
import com.knowledgeflow.billing.repository.CommercialPlanRepository;
import com.knowledgeflow.billing.repository.ConsumptionEventRepository;
import com.knowledgeflow.billing.repository.OrganizationPlanRepository;
import com.knowledgeflow.cases.dto.KnowledgeCaseCreateRequest;
import com.knowledgeflow.cases.repository.KnowledgeCaseCommentRepository;
import com.knowledgeflow.cases.repository.KnowledgeCaseRepository;
import com.knowledgeflow.cases.repository.KnowledgeCaseVersionRepository;
import com.knowledgeflow.cases.service.KnowledgeCaseService;
import com.knowledgeflow.clients.dto.ClientCreateRequest;
import com.knowledgeflow.clients.dto.ClientDetailResponse;
import com.knowledgeflow.clients.repository.ClientPortalUserRepository;
import com.knowledgeflow.clients.repository.ClientRepository;
import com.knowledgeflow.clients.service.ClientService;
import com.knowledgeflow.audit.repository.AuditEventRepository;
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
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class CommercialPlanServiceIntegrationTest {

    @Autowired private CommercialPlanService commercialPlanService;
    @Autowired private KnowledgeCaseService knowledgeCaseService;
    @Autowired private ClientService clientService;

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

        organization = organizationRepository.save(new Organization("Billing Admin Test Org", null));
        user = userRepository.save(new User(
                "admin-%s@knowledgeflow.test".formatted(UUID.randomUUID()),
                "Admin User", "hash-not-used"));
    }

    @Test
    void createPlanStoresPlanWithCorrectFields() {
        CommercialPlanResponse response = commercialPlanService.createPlan(
                new CommercialPlanCreateRequest("Starter", PlanType.TICKET, 10, null, 5));

        assertThat(response.id()).isNotNull();
        assertThat(response.name()).isEqualTo("Starter");
        assertThat(response.planType()).isEqualTo(PlanType.TICKET);
        assertThat(response.maxCases()).isEqualTo(10);
        assertThat(response.maxInteractions()).isNull();
        assertThat(response.maxPortalUsers()).isEqualTo(5);
        assertThat(response.active()).isTrue();
    }

    @Test
    void listPlansReturnsAllPlans() {
        commercialPlanService.createPlan(new CommercialPlanCreateRequest("Plan A", PlanType.MONTHLY, null, null, null));
        commercialPlanService.createPlan(new CommercialPlanCreateRequest("Plan B", PlanType.TICKET, 5, null, null));

        assertThat(commercialPlanService.listPlans()).hasSize(2);
    }

    @Test
    void assignPlanToOrganizationAndRetrieveActive() {
        CommercialPlanResponse plan = commercialPlanService.createPlan(
                new CommercialPlanCreateRequest("Professional", PlanType.MONTHLY, null, 100, null));

        Instant start = Instant.now().minusSeconds(60);
        OrganizationPlanResponse assigned = commercialPlanService.assignPlan(organization.getId(),
                new AssignPlanRequest(plan.id(), start, null));

        assertThat(assigned.organizationId()).isEqualTo(organization.getId());
        assertThat(assigned.plan().id()).isEqualTo(plan.id());
        assertThat(assigned.endsAt()).isNull();

        OrganizationPlanResponse active = commercialPlanService.getActivePlan(organization.getId());
        assertThat(active.id()).isEqualTo(assigned.id());
    }

    @Test
    void getActivePlanThrowsWhenNoPlanAssigned() {
        assertThatThrownBy(() -> commercialPlanService.getActivePlan(organization.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No active plan");
    }

    @Test
    void getActivePlanThrowsForExpiredPlan() {
        CommercialPlanResponse plan = commercialPlanService.createPlan(
                new CommercialPlanCreateRequest("Expired", PlanType.TICKET, 5, null, null));
        commercialPlanService.assignPlan(organization.getId(), new AssignPlanRequest(
                plan.id(),
                Instant.now().minusSeconds(120),
                Instant.now().minusSeconds(1)  // already expired
        ));

        assertThatThrownBy(() -> commercialPlanService.getActivePlan(organization.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No active plan");
    }

    @Test
    void consumptionSummaryReflectsCaseCreations() {
        CommercialPlanResponse plan = commercialPlanService.createPlan(
                new CommercialPlanCreateRequest("Unlimited", PlanType.MONTHLY, null, null, null));
        commercialPlanService.assignPlan(organization.getId(), new AssignPlanRequest(
                plan.id(), Instant.now().minusSeconds(60), null));

        ClientDetailResponse client = clientService.create(organization.getId(), user.getId(),
                new ClientCreateRequest("Cliente", null, null, null, null, null, null, null, null, null, null, null, null));

        knowledgeCaseService.create(organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(client.id(), "Caso 1", "Pergunta", null));
        knowledgeCaseService.create(organization.getId(), user.getId(),
                new KnowledgeCaseCreateRequest(client.id(), "Caso 2", "Pergunta", null));

        ConsumptionSummaryResponse summary = commercialPlanService.getConsumptionSummary(organization.getId());

        assertThat(summary.casesCreated()).isEqualTo(2);
        assertThat(summary.aiInteractions()).isEqualTo(0);
        assertThat(summary.maxCases()).isNull();
        assertThat(summary.organizationId()).isEqualTo(organization.getId());
    }

    @Test
    void assignPlanValidatesEndsAtAfterStartsAt() {
        CommercialPlanResponse plan = commercialPlanService.createPlan(
                new CommercialPlanCreateRequest("Plan", PlanType.MONTHLY, null, null, null));

        Instant now = Instant.now();
        assertThatThrownBy(() -> commercialPlanService.assignPlan(organization.getId(),
                new AssignPlanRequest(plan.id(), now, now.minusSeconds(1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("endsAt must be after startsAt");
    }
}
