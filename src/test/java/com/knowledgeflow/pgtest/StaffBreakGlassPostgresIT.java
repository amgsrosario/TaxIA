package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.entity.OrganizationUser;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.organizations.repository.OrganizationUserRepository;
import com.knowledgeflow.security.StaffSessionVerifier;
import com.knowledgeflow.users.credentials.BreakGlassResult;
import com.knowledgeflow.users.credentials.StaffBreakGlassService;
import com.knowledgeflow.users.entity.User;
import com.knowledgeflow.users.enums.RoleName;
import com.knowledgeflow.users.repository.RoleRepository;
import com.knowledgeflow.users.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-006 break-glass on a disposable PostgreSQL whose database is <i>named</i>
 * {@code knowledgeflow_pilot} (so the datasource gate accepts it) — NOT the real pilot database.
 * Also proves that the JWT secret guard, which always applies to knowledgeflow_pilot, accepts the
 * fictitious policy-compliant pgtest key (the context boots).
 *
 * Run: mvn verify -Ppgtest -Dit.test=StaffBreakGlassPostgresIT
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
class StaffBreakGlassPostgresIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16").withDatabaseName("knowledgeflow_pilot");

    private static final String ORIGINAL = "original-admin-password";
    private static final String TEMPORARY = "temporaria-break-glass-1";

    @Autowired StaffBreakGlassService service;
    @Autowired StaffSessionVerifier verifier;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired OrganizationUserRepository organizationUserRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;

    private User staff(Organization org, String email, RoleName role) {
        User user = userRepository.save(new User(email, email, passwordEncoder.encode(ORIGINAL)));
        if (role != null) {
            organizationUserRepository.save(new OrganizationUser(org, user, roleRepository.findByName(role).orElseThrow()));
        }
        return user;
    }

    private Organization org() {
        return organizationRepository.save(new Organization("Org BG " + UUID.randomUUID(), null));
    }

    private BreakGlassResult recover(String email, String password, String confirmation) {
        return service.recover(true, email, "admin sem acesso", password, confirmation, "host-teste");
    }

    @Test
    @DisplayName("34-35. sucesso: RECOVERED, must_change, sessões revogadas, auditoria técnica sem segredos")
    void recoversActiveAdmin() {
        Organization org = org();
        User admin = staff(org, "admin-ok@bg.test", RoleName.ADMIN);
        assertThat(verifier.verify(admin.getId(), org.getId(), 0)).isPresent();

        BreakGlassResult result = recover("ADMIN-OK@bg.test", TEMPORARY, TEMPORARY);

        assertThat(result.outcome()).isEqualTo(BreakGlassResult.Outcome.RECOVERED);
        String rendered = result.render();
        assertThat(rendered).endsWith("RESULT=RECOVERED").contains("must_change_password=true")
                .doesNotContain(TEMPORARY).doesNotContain(ORIGINAL).doesNotContain("$2a$").doesNotContain("eyJ");

        User after = userRepository.findById(admin.getId()).orElseThrow();
        assertThat(after.isMustChangePassword()).isTrue();
        assertThat(after.getTokenVersion()).isEqualTo(1);
        assertThat(passwordEncoder.matches(TEMPORARY, after.getPasswordHash())).isTrue();
        assertThat(verifier.verify(admin.getId(), org.getId(), 0)).isEmpty();
        assertThat(verifier.verify(admin.getId(), org.getId(), 1).orElseThrow().mustChangePassword()).isTrue();

        List<Map<String, Object>> audit = jdbc.queryForList(
                "SELECT user_id, organization_id, metadata FROM audit_events WHERE action = 'USER_BREAK_GLASS_RESET'"
                        + " AND entity_id = ?", admin.getId());
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("user_id")).isNull();
        assertThat(audit.get(0).get("organization_id")).isEqualTo(org.getId());
        assertThat((String) audit.get(0).get("metadata")).contains("actor=break-glass")
                .contains("target=" + admin.getId()).contains("reason=admin sem acesso").contains("host=host-teste")
                .doesNotContain(TEMPORARY).doesNotContain("$2a$");
    }

    @Test
    @DisplayName("recusas: email inexistente, não-ADMIN, ADMIN desactivado, conta de serviço, ambiguidade")
    void refusesInvalidTargets() {
        Organization org = org();
        staff(org, "autor@bg.test", RoleName.AUTHOR);
        User disabled = staff(org, "desactivado@bg.test", RoleName.ADMIN);
        disabled.disable();
        userRepository.save(disabled);
        jdbc.update("INSERT INTO users (id, email, full_name, password_hash, status, created_at, updated_at)"
                + " VALUES (?, 'svc@bg.test', 'svc', 'LOCKED-NO-LOGIN-SERVICE-ACCOUNT', 'ACTIVE', now(), now())",
                UUID.randomUUID());
        staff(org, "Duplo@bg.test", RoleName.ADMIN);
        staff(org, "duplo@bg.test", RoleName.ADMIN);
        User twoOrgs = staff(org, "duas-orgs@bg.test", RoleName.ADMIN);
        organizationUserRepository.save(new OrganizationUser(org(), twoOrgs,
                roleRepository.findByName(RoleName.ADMIN).orElseThrow()));

        for (String email : List.of("ninguem@bg.test", "autor@bg.test", "desactivado@bg.test", "svc@bg.test",
                "duplo@bg.test", "duas-orgs@bg.test")) {
            BreakGlassResult result = recover(email, TEMPORARY, TEMPORARY);
            assertThat(result.outcome()).as(email).isEqualTo(BreakGlassResult.Outcome.BLOCKED);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'USER_BREAK_GLASS_RESET'"
                + " AND organization_id = ?", Integer.class, org.getId())).isZero();
    }

    @Test
    @DisplayName("recusas: confirmação diferente, política, igual à actual, motivo em falta")
    void refusesInvalidPasswordsAndMissingReason() {
        Organization org = org();
        User admin = staff(org, "admin-pw@bg.test", RoleName.ADMIN);
        assertThat(recover("admin-pw@bg.test", TEMPORARY, TEMPORARY + "x").outcome())
                .isEqualTo(BreakGlassResult.Outcome.BLOCKED);
        BreakGlassResult shortOne = recover("admin-pw@bg.test", "curta", "curta");
        assertThat(shortOne.outcome()).isEqualTo(BreakGlassResult.Outcome.BLOCKED);
        assertThat(shortOne.render()).doesNotContain("curta\n").contains("password policy");
        assertThat(recover("admin-pw@bg.test", ORIGINAL, ORIGINAL).outcome())
                .isEqualTo(BreakGlassResult.Outcome.BLOCKED);
        assertThat(service.recover(true, "admin-pw@bg.test", " ", TEMPORARY, TEMPORARY, "h").outcome())
                .isEqualTo(BreakGlassResult.Outcome.BLOCKED);
        assertThat(userRepository.findById(admin.getId()).orElseThrow().getTokenVersion()).isZero();
    }
}
