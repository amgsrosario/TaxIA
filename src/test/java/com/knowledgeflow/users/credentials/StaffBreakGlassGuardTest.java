package com.knowledgeflow.users.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.audit.repository.AuditEventRepository;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.entity.OrganizationUser;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.organizations.repository.OrganizationUserRepository;
import com.knowledgeflow.support.H2TestDatabaseCleaner;
import com.knowledgeflow.users.entity.Role;
import com.knowledgeflow.users.entity.User;
import com.knowledgeflow.users.enums.RoleName;
import com.knowledgeflow.users.repository.RoleRepository;
import com.knowledgeflow.users.repository.UserRepository;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADR-006 break-glass fail-closed gates on H2 (not the pilot): disabled by default and refused
 * on any datasource other than knowledgeflow_pilot — nothing is written.
 */
@SpringBootTest
@ActiveProfiles("test")
class StaffBreakGlassGuardTest {

    private static final String NEW_PASSWORD = "nova-temporaria-segura";

    @Autowired StaffBreakGlassService service;
    @Autowired DataSource dataSource;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired OrganizationUserRepository organizationUserRepository;
    @Autowired AuditEventRepository auditEventRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private User admin;

    @BeforeEach
    void setUp() {
        H2TestDatabaseCleaner.clean(dataSource);
        Role role = roleRepository.findByName(RoleName.ADMIN)
                .orElseGet(() -> roleRepository.save(new Role(RoleName.ADMIN, "ADMIN")));
        Organization org = organizationRepository.save(new Organization("Org BG", null));
        admin = userRepository.save(new User("admin@bg.test", "Admin", passwordEncoder.encode("original-password-1")));
        organizationUserRepository.save(new OrganizationUser(org, admin, role));
    }

    private void assertUntouched() {
        User after = userRepository.findById(admin.getId()).orElseThrow();
        assertThat(after.getTokenVersion()).isZero();
        assertThat(after.isMustChangePassword()).isFalse();
        assertThat(passwordEncoder.matches("original-password-1", after.getPasswordHash())).isTrue();
        assertThat(auditEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("32. break-glass desligado por omissão → BLOCKED, sem escrita")
    void disabledByDefault() {
        BreakGlassResult result = service.recover(false, "admin@bg.test", "perdi acesso",
                NEW_PASSWORD, NEW_PASSWORD, "host");
        assertThat(result.outcome()).isEqualTo(BreakGlassResult.Outcome.BLOCKED);
        assertThat(result.render()).endsWith("RESULT=BLOCKED").contains("disabled");
        assertUntouched();
    }

    @Test
    @DisplayName("33. datasource que não é knowledgeflow_pilot → BLOCKED, sem escrita")
    void wrongDatasourceBlocked() {
        BreakGlassResult result = service.recover(true, "admin@bg.test", "perdi acesso",
                NEW_PASSWORD, NEW_PASSWORD, "host");
        assertThat(result.outcome()).isEqualTo(BreakGlassResult.Outcome.BLOCKED);
        assertThat(result.render()).contains("knowledgeflow_pilot").doesNotContain(NEW_PASSWORD);
        assertUntouched();
    }
}
