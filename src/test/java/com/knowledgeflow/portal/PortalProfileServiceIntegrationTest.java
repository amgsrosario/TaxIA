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
import com.knowledgeflow.cases.repository.KnowledgeCaseCommentRepository;
import com.knowledgeflow.cases.repository.KnowledgeCaseRepository;
import com.knowledgeflow.cases.repository.KnowledgeCaseVersionRepository;
import com.knowledgeflow.clients.dto.ClientCreateRequest;
import com.knowledgeflow.clients.dto.ClientDetailResponse;
import com.knowledgeflow.clients.dto.ClientPortalUserCreateRequest;
import com.knowledgeflow.clients.dto.ClientPortalUserResponse;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class PortalProfileServiceIntegrationTest {

    @Autowired private PortalProfileService portalProfileService;
    @Autowired private ClientService clientService;
    @Autowired private ClientPortalUserService clientPortalUserService;
    @Autowired private PasswordEncoder passwordEncoder;

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
    private UUID portalUserId;

    private static final String PORTAL_EMAIL = "portal@example.com";
    private static final String PORTAL_PASSWORD = "senha-original-123";

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

        organization = organizationRepository.save(new Organization("Portal Profile Test Org", null));
        User user = userRepository.save(new User(
                "staff-%s@knowledgeflow.test".formatted(UUID.randomUUID()),
                "Staff", "hash-not-used"));

        CommercialPlan plan = commercialPlanRepository.save(
                new CommercialPlan("Unlimited", PlanType.MONTHLY, null, null, null));
        organizationPlanRepository.save(
                new OrganizationPlan(organization.getId(), plan, Instant.now().minusSeconds(60), null));

        client = clientService.create(organization.getId(), user.getId(),
                new ClientCreateRequest("Empresa", null, null, null, null,
                        null, null, null, null, null, null, null, null));

        ClientPortalUserResponse portalUser = clientPortalUserService.create(
                organization.getId(), client.id(),
                new ClientPortalUserCreateRequest(PORTAL_EMAIL, PORTAL_PASSWORD));
        portalUserId = portalUser.id();
    }

    @Test
    void getProfileReturnsCorrectProfileData() {
        ClientPortalUserResponse profile = portalProfileService.getProfile(
                portalUserId, organization.getId());

        assertThat(profile.id()).isEqualTo(portalUserId);
        assertThat(profile.email()).isEqualTo(PORTAL_EMAIL);
        assertThat(profile.clientId()).isEqualTo(client.id());
        assertThat(profile.organizationId()).isEqualTo(organization.getId());
    }

    @Test
    void changePasswordSucceedsWithCorrectCurrentPassword() {
        ClientPortalUserResponse updated = portalProfileService.changePassword(
                portalUserId, organization.getId(),
                new PortalChangePasswordRequest(PORTAL_PASSWORD, "nova-senha-segura"));

        assertThat(updated.id()).isEqualTo(portalUserId);

        // Verify the new password hash works
        var raw = clientPortalUserRepository.findById(portalUserId).orElseThrow();
        assertThat(passwordEncoder.matches("nova-senha-segura", raw.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(PORTAL_PASSWORD, raw.getPasswordHash())).isFalse();
    }

    @Test
    void changePasswordFailsWithWrongCurrentPassword() {
        assertThatThrownBy(() -> portalProfileService.changePassword(
                portalUserId, organization.getId(),
                new PortalChangePasswordRequest("senha-errada", "nova-senha")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Current password is incorrect");
    }

    @Test
    void getProfileThrowsForUnknownPortalUser() {
        assertThatThrownBy(() ->
                portalProfileService.getProfile(UUID.randomUUID(), organization.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Portal user not found");
    }
}
