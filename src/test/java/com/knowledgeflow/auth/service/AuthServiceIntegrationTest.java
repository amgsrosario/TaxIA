package com.knowledgeflow.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.auth.dto.AuthResponse;
import com.knowledgeflow.auth.dto.BootstrapAdminRequest;
import com.knowledgeflow.auth.dto.LoginRequest;
import com.knowledgeflow.cases.repository.KnowledgeCaseRepository;
import com.knowledgeflow.cases.repository.KnowledgeCaseVersionRepository;
import com.knowledgeflow.clients.repository.ClientRepository;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.organizations.repository.OrganizationUserRepository;
import com.knowledgeflow.users.repository.UserRepository;
import com.knowledgeflow.users.entity.Role;
import com.knowledgeflow.users.enums.RoleName;
import com.knowledgeflow.users.repository.RoleRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class AuthServiceIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private OrganizationUserRepository organizationUserRepository;

    @Autowired
    private KnowledgeCaseVersionRepository knowledgeCaseVersionRepository;

    @Autowired
    private KnowledgeCaseRepository knowledgeCaseRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @BeforeEach
    void setUp() {
        knowledgeCaseVersionRepository.deleteAll();
        knowledgeCaseRepository.deleteAll();
        clientRepository.deleteAll();
        organizationUserRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
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
        ));

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
        ));

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
        ));

        assertThatThrownBy(() -> authService.bootstrapAdmin(new BootstrapAdminRequest(
                "KnowledgeFlow Second",
                null,
                "second-%s@knowledgeflow.test".formatted(UUID.randomUUID()),
                "Second Admin",
                "password-123"
        ))).isInstanceOf(BusinessException.class)
                .hasMessage("Bootstrap admin can only be used before users exist");
    }
}
