package com.knowledgeflow.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.auth.dto.AuthResponse;
import com.knowledgeflow.auth.dto.BootstrapAdminRequest;
import com.knowledgeflow.auth.dto.LoginRequest;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.organizations.repository.OrganizationUserRepository;
import com.knowledgeflow.support.H2TestDatabaseCleaner;
import com.knowledgeflow.users.entity.Role;
import com.knowledgeflow.users.enums.RoleName;
import com.knowledgeflow.users.repository.RoleRepository;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class AuthServiceIntegrationTest {

    private static final String TEST_BOOTSTRAP_SECRET = "test-bootstrap-secret";

    @Autowired
    private AuthService authService;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private OrganizationUserRepository organizationUserRepository;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        H2TestDatabaseCleaner.clean(dataSource);
        roleRepository.findByName(RoleName.ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(RoleName.ADMIN, "Administra a organizacao e utilizadores")));
    }

    @Test
    void bootstrapAdminCreatesOrganizationUserAndReturnsToken() {
        String email = "admin-%s@knowledgeflow.test".formatted(UUID.randomUUID());

        AuthResponse response = authService.bootstrapAdmin(new BootstrapAdminRequest(
                "KnowledgeFlow Test",
                null,
                email,
                "KnowledgeFlow Admin",
                "password-123"
        ), TEST_BOOTSTRAP_SECRET);

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.email()).isEqualTo(email);
        assertThat(response.roles()).containsExactly("ADMIN");
        assertThat(organizationUserRepository.findByUserIdAndDeletedAtIsNull(response.userId())).hasSize(1);
    }

    @Test
    void loginReturnsTokenForValidCredentials() {
        String email = "login-%s@knowledgeflow.test".formatted(UUID.randomUUID());
        authService.bootstrapAdmin(new BootstrapAdminRequest(
                "KnowledgeFlow Login Test",
                null,
                email,
                "Login Admin",
                "password-123"
        ), TEST_BOOTSTRAP_SECRET);

        AuthResponse response = authService.login(new LoginRequest(email, "password-123"));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.email()).isEqualTo(email);
        assertThat(response.roles()).containsExactly("ADMIN");
    }

    @Test
    void bootstrapAdminCanOnlyRunBeforeUsersExist() {
        authService.bootstrapAdmin(new BootstrapAdminRequest(
                "KnowledgeFlow First",
                null,
                "first-%s@knowledgeflow.test".formatted(UUID.randomUUID()),
                "First Admin",
                "password-123"
        ), TEST_BOOTSTRAP_SECRET);

        assertThatThrownBy(() -> authService.bootstrapAdmin(new BootstrapAdminRequest(
                "KnowledgeFlow Second",
                null,
                "second-%s@knowledgeflow.test".formatted(UUID.randomUUID()),
                "Second Admin",
                "password-123"
        ), TEST_BOOTSTRAP_SECRET)).isInstanceOf(BusinessException.class)
                .hasMessage("Bootstrap admin can only be used before users exist");
    }

    @Test
    void bootstrapAdminRejectsWrongSecret() {
        assertThatThrownBy(() -> authService.bootstrapAdmin(new BootstrapAdminRequest(
                "KnowledgeFlow Wrong Secret",
                null,
                "wrong-%s@knowledgeflow.test".formatted(UUID.randomUUID()),
                "Wrong Admin",
                "password-123"
        ), "segredo-errado")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid bootstrap secret");
    }

    @Test
    void bootstrapAdminRejectsMissingSecret() {
        assertThatThrownBy(() -> authService.bootstrapAdmin(new BootstrapAdminRequest(
                "KnowledgeFlow No Secret",
                null,
                "nosecret-%s@knowledgeflow.test".formatted(UUID.randomUUID()),
                "No Secret Admin",
                "password-123"
        ), null)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid bootstrap secret");
    }
}
