package com.knowledgeflow.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.billing.entity.CommercialPlan;
import com.knowledgeflow.billing.entity.OrganizationPlan;
import com.knowledgeflow.billing.enums.PlanType;
import com.knowledgeflow.billing.repository.CommercialPlanRepository;
import com.knowledgeflow.billing.repository.OrganizationPlanRepository;
import com.knowledgeflow.clients.dto.ClientCreateRequest;
import com.knowledgeflow.clients.dto.ClientDetailResponse;
import com.knowledgeflow.clients.dto.ClientPortalUserCreateRequest;
import com.knowledgeflow.clients.service.ClientPortalUserService;
import com.knowledgeflow.clients.service.ClientService;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.users.entity.Role;
import com.knowledgeflow.users.enums.RoleName;
import com.knowledgeflow.users.entity.User;
import com.knowledgeflow.users.repository.RoleRepository;
import com.knowledgeflow.users.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class H2TestDatabaseCleanerIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ClientService clientService;
    @Autowired private ClientPortalUserService clientPortalUserService;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CommercialPlanRepository commercialPlanRepository;
    @Autowired private OrganizationPlanRepository organizationPlanRepository;
    @Autowired private RoleRepository roleRepository;

    @BeforeEach
    void setUp() {
        H2TestDatabaseCleaner.clean(dataSource);
    }

    @Test
    void emptiesEveryMutableTableAcrossForeignKeys() {
        // client_portal_users -> clients -> organizations/users: the chain that made
        // hand-written deleteAll() lists fail when a class did not know about the child table.
        seedClientWithPortalUser();
        assertThat(rowCount("client_portal_users")).isEqualTo(1);

        H2TestDatabaseCleaner.clean(dataSource);

        assertThat(mutableTables()).isNotEmpty()
                .allSatisfy(table -> assertThat(rowCount(table)).as(table).isZero());
    }

    @Test
    void preservesReferenceRoles() {
        roleRepository.findByName(RoleName.ADMIN)
                .orElseGet(() -> roleRepository.save(
                        new Role(RoleName.ADMIN, "Administra a organizacao e utilizadores")));

        H2TestDatabaseCleaner.clean(dataSource);

        assertThat(roleRepository.findByName(RoleName.ADMIN)).isPresent();
    }

    @Test
    void leavesReferentialIntegrityEnabled() {
        seedClientWithPortalUser();
        H2TestDatabaseCleaner.clean(dataSource);
        seedClientWithPortalUser();

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM organizations"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void seedClientWithPortalUser() {
        Organization organization = organizationRepository.save(new Organization("Cleaner Test Org", null));
        User user = userRepository.save(new User(
                "cleaner-%s@knowledgeflow.test".formatted(UUID.randomUUID()),
                "Cleaner User", "hash-not-used"));
        CommercialPlan plan = commercialPlanRepository.save(
                new CommercialPlan("Unlimited", PlanType.MONTHLY, null, null, null));
        organizationPlanRepository.save(
                new OrganizationPlan(organization.getId(), plan, Instant.now().minusSeconds(60), null));
        ClientDetailResponse client = clientService.create(organization.getId(), user.getId(),
                new ClientCreateRequest(
                        "Empresa Cliente", null, null, null, null, null, null, null, null, null, null, null, null));
        clientPortalUserService.create(organization.getId(), client.id(),
                new ClientPortalUserCreateRequest(
                        "cliente-%s@example.com".formatted(UUID.randomUUID()), "senha-segura-123"));
    }

    private List<String> mutableTables() {
        return jdbcTemplate.queryForList(
                        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                                + "WHERE TABLE_SCHEMA = SCHEMA() AND TABLE_TYPE = 'BASE TABLE'",
                        String.class)
                .stream()
                .filter(table -> !H2TestDatabaseCleaner.PRESERVED_TABLES.contains(table))
                .toList();
    }

    private int rowCount(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM \"" + table + "\"", Integer.class);
    }
}
