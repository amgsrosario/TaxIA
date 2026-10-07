package com.knowledgeflow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knowledgeflow.audit.entity.AuditEvent;
import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.repository.AuditEventRepository;
import com.knowledgeflow.users.entity.User;
import com.knowledgeflow.users.enums.RoleName;
import com.knowledgeflow.users.enums.UserStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADR-006 credential and session flows over HTTP: own password change, ADMIN reset with forced
 * change, logout-all, ADMIN revoke, disable/reactivate, role changes, last-ADMIN and self guards,
 * and audit events that never carry a password, hash or token.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffCredentialFlowsHttpTest extends StaffSecurityHttpTestSupport {

    private static final String ME = "/api/v1/auth/me";
    private static final String PASSWORD = "/api/v1/auth/password";
    private static final String LOGOUT_ALL = "/api/v1/auth/logout-all";
    private static final String ADMIN_ONLY = "/api/v1/admin/users";
    private static final String NEW_PASSWORD = "uma-password-nova-e-longa";
    private static final String TEMP_PASSWORD = "temporaria-entregue-fora";

    @Autowired AuditEventRepository auditEventRepository;

    private Map<String, String> change(String current, String next) {
        return Map.of("currentPassword", current, "newPassword", next);
    }

    private void assertLoginFails(String email, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isUnauthorized());
    }

    private List<AuditEvent> audit(AuditAction action) {
        return auditEventRepository.findAll().stream().filter(e -> e.getAction() == action).toList();
    }

    /** No audit row may contain any password used in this class, a BCrypt hash or a JWT. */
    private void assertNoSecretsInAudit(String... tokens) {
        for (AuditEvent event : auditEventRepository.findAll()) {
            String metadata = String.valueOf(event.getMetadata());
            for (String secret : List.of(ADMIN_PASSWORD, SECOND_ADMIN_PASSWORD, AUTHOR_PASSWORD, NEW_PASSWORD,
                    TEMP_PASSWORD)) {
                assertThat(metadata).doesNotContain(secret);
            }
            assertThat(metadata).doesNotContain("$2a$").doesNotContain("$2b$").doesNotContain("eyJ");
            for (String token : tokens) {
                assertThat(metadata).doesNotContain(token);
            }
        }
    }

    // ------------------------------------------------------------ own password

    @Test
    @DisplayName("16/21. mudança da própria password: 204, token actual morre, password antiga falha, nova entra")
    void ownPasswordChange() throws Exception {
        String token = login("author@cred.test", AUTHOR_PASSWORD);
        postWith(token, PASSWORD, change(AUTHOR_PASSWORD, NEW_PASSWORD)).andExpect(status().isNoContent());

        getWith(token, ME).andExpect(status().isUnauthorized());
        assertLoginFails("author@cred.test", AUTHOR_PASSWORD);
        String fresh = login("author@cred.test", NEW_PASSWORD);
        getWith(fresh, ME).andExpect(status().isOk());

        User user = reload(author);
        assertThat(user.getTokenVersion()).isEqualTo(1);
        assertThat(user.isMustChangePassword()).isFalse();
        assertThat(audit(AuditAction.USER_PASSWORD_CHANGED)).hasSize(1);
        assertNoSecretsInAudit(token, fresh);
    }

    @Test
    @DisplayName("17-20. password actual errada, nova curta, igual à actual ou ao email → 400 sem alterações")
    void ownPasswordChangeValidation() throws Exception {
        String token = login("author@cred.test", AUTHOR_PASSWORD);
        postWith(token, PASSWORD, change("errada-errada-errada", NEW_PASSWORD)).andExpect(status().isBadRequest());
        postWith(token, PASSWORD, change(AUTHOR_PASSWORD, "curta-11chr")).andExpect(status().isBadRequest());
        postWith(token, PASSWORD, change(AUTHOR_PASSWORD, AUTHOR_PASSWORD)).andExpect(status().isBadRequest());
        postWith(token, PASSWORD, change(AUTHOR_PASSWORD, "AUTHOR@cred.test")).andExpect(status().isBadRequest());
        postWith(token, PASSWORD, change(AUTHOR_PASSWORD, "x".repeat(73))).andExpect(status().isBadRequest());

        String body = postWith(token, PASSWORD, change(AUTHOR_PASSWORD, "curta-11chr"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("curta-11chr").doesNotContain(AUTHOR_PASSWORD);

        getWith(token, ME).andExpect(status().isOk());
        assertThat(reload(author).getTokenVersion()).isZero();
        assertThat(audit(AuditAction.USER_PASSWORD_CHANGED)).isEmpty();
    }

    // ------------------------------------------------------------ ADMIN reset

    @Test
    @DisplayName("22-24. reset ADMIN: sessões antigas morrem, login fica restrito à mudança de password")
    void adminResetForcesPasswordChange() throws Exception {
        String adminToken = login("admin@cred.test", ADMIN_PASSWORD);
        String authorOld = login("author@cred.test", AUTHOR_PASSWORD);

        postWith(adminToken, adminUsers(author.getId(), "password-reset"),
                Map.of("temporaryPassword", TEMP_PASSWORD, "reason", "perdeu a password"))
                .andExpect(status().isNoContent());
        getWith(authorOld, ME).andExpect(status().isUnauthorized());
        assertLoginFails("author@cred.test", AUTHOR_PASSWORD);

        var login = loginBody("author@cred.test", TEMP_PASSWORD);
        assertThat(login.get("mustChangePassword").asBoolean()).isTrue();
        String restricted = login.get("accessToken").asText();

        // Restricted session: only /me, password change and logout-all.
        getWith(restricted, ME).andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.roles").isEmpty());
        getWith(restricted, "/api/v1/clients").andExpect(status().isForbidden());
        getWith(restricted, "/api/v1/knowledge-cases").andExpect(status().isForbidden());
        getWith(restricted, "/api/v1/admin/knowledge/qa").andExpect(status().isForbidden());

        postWith(restricted, PASSWORD, change(TEMP_PASSWORD, NEW_PASSWORD)).andExpect(status().isNoContent());
        getWith(restricted, ME).andExpect(status().isUnauthorized());
        var after = loginBody("author@cred.test", NEW_PASSWORD);
        assertThat(after.get("mustChangePassword").asBoolean()).isFalse();
        getWith(after.get("accessToken").asText(), "/api/v1/clients").andExpect(status().isOk());

        List<AuditEvent> resets = audit(AuditAction.USER_PASSWORD_RESET);
        assertThat(resets).hasSize(1);
        assertThat(resets.get(0).getUserId()).isEqualTo(admin.getId());
        assertThat(resets.get(0).getEntityId()).isEqualTo(author.getId());
        assertThat(resets.get(0).getMetadata()).contains("reason=perdeu a password")
                .contains("organization=" + org.getId());
        assertNoSecretsInAudit(adminToken, authorOld, restricted);
    }

    @Test
    @DisplayName("reset ADMIN: política, alvo de outra organização (404), o próprio (409), não-ADMIN (403)")
    void adminResetGuards() throws Exception {
        String adminToken = login("admin@cred.test", ADMIN_PASSWORD);
        postWith(adminToken, adminUsers(author.getId(), "password-reset"),
                Map.of("temporaryPassword", "curta", "reason", "x")).andExpect(status().isBadRequest());
        postWith(adminToken, adminUsers(author.getId(), "password-reset"),
                Map.of("temporaryPassword", TEMP_PASSWORD)).andExpect(status().isBadRequest());
        postWith(adminToken, adminUsers(otherOrgAdmin.getId(), "password-reset"),
                Map.of("temporaryPassword", TEMP_PASSWORD, "reason", "x")).andExpect(status().isNotFound());
        postWith(adminToken, adminUsers(admin.getId(), "password-reset"),
                Map.of("temporaryPassword", TEMP_PASSWORD, "reason", "x")).andExpect(status().isConflict());

        String authorToken = login("author@cred.test", AUTHOR_PASSWORD);
        postWith(authorToken, adminUsers(admin.getId(), "password-reset"),
                Map.of("temporaryPassword", TEMP_PASSWORD, "reason", "x")).andExpect(status().isForbidden());

        assertThat(reload(author).getTokenVersion()).isZero();
        assertThat(reload(otherOrgAdmin).getTokenVersion()).isZero();
        assertThat(audit(AuditAction.USER_PASSWORD_RESET)).isEmpty();
    }

    @Test
    @DisplayName("reset ADMIN→ADMIN recusado (409), mesmo com o alvo desactivado; ex-ADMIN despromovido pode ser reposto")
    void adminAccountsAreNeverResetByAnotherAdmin() throws Exception {
        String adminToken = login("admin@cred.test", ADMIN_PASSWORD);
        String secondToken = login("admin2@cred.test", SECOND_ADMIN_PASSWORD);

        postWith(adminToken, adminUsers(secondAdmin.getId(), "password-reset"),
                Map.of("temporaryPassword", TEMP_PASSWORD, "reason", "x")).andExpect(status().isConflict());
        getWith(secondToken, ME).andExpect(status().isOk());
        User untouched = reload(secondAdmin);
        assertThat(untouched.getTokenVersion()).isZero();
        assertThat(untouched.isMustChangePassword()).isFalse();
        assertLoginFails("admin2@cred.test", TEMP_PASSWORD);

        // A disabled account that still holds the ADMIN role is refused as well.
        postWith(adminToken, adminUsers(secondAdmin.getId(), "disable"), Map.of("reason", "x"))
                .andExpect(status().isNoContent());
        postWith(adminToken, adminUsers(secondAdmin.getId(), "password-reset"),
                Map.of("temporaryPassword", TEMP_PASSWORD, "reason", "x")).andExpect(status().isConflict());
        assertThat(audit(AuditAction.USER_PASSWORD_RESET)).isEmpty();

        // Once the ADMIN role is removed, the account is an ordinary staff account again.
        postWith(adminToken, adminUsers(secondAdmin.getId(), "reactivate"), Map.of("reason", "x"))
                .andExpect(status().isNoContent());
        putWith(adminToken, adminUsers(secondAdmin.getId(), "roles"), Map.of("roles", List.of("VIEWER"), "reason", "x"))
                .andExpect(status().isNoContent());
        postWith(adminToken, adminUsers(secondAdmin.getId(), "password-reset"),
                Map.of("temporaryPassword", TEMP_PASSWORD, "reason", "x")).andExpect(status().isNoContent());
        assertThat(reload(secondAdmin).isMustChangePassword()).isTrue();

        // …and it cannot be promoted back to ADMIN while that temporary password is pending
        // (closes demote → reset → re-grant).
        putWith(adminToken, adminUsers(secondAdmin.getId(), "roles"), Map.of("roles", List.of("ADMIN"), "reason", "x"))
                .andExpect(status().isConflict());
        assertThat(organizationUserRepository.findByUserIdAndDeletedAtIsNull(secondAdmin.getId()).stream()
                .map(m -> m.getRole().getName()).toList()).containsExactly(RoleName.VIEWER);
        assertThat(audit(AuditAction.USER_ROLES_CHANGED)).hasSize(1);

        // Allowed path: once the holder has set their own password, ADMIN can be granted again.
        String restricted = login("admin2@cred.test", TEMP_PASSWORD);
        postWith(restricted, PASSWORD, change(TEMP_PASSWORD, NEW_PASSWORD)).andExpect(status().isNoContent());
        putWith(adminToken, adminUsers(secondAdmin.getId(), "roles"), Map.of("roles", List.of("ADMIN"), "reason", "x"))
                .andExpect(status().isNoContent());
        getWith(login("admin2@cred.test", NEW_PASSWORD), ADMIN_ONLY).andExpect(status().isOk());
    }

    // ------------------------------------------------------------ revoke

    @Test
    @DisplayName("25. logout-all: todos os tokens do próprio morrem, outros utilizadores não")
    void logoutAll() throws Exception {
        String first = login("author@cred.test", AUTHOR_PASSWORD);
        String second = login("author@cred.test", AUTHOR_PASSWORD);
        String adminToken = login("admin@cred.test", ADMIN_PASSWORD);

        postWith(first, LOGOUT_ALL, null).andExpect(status().isNoContent());
        getWith(first, ME).andExpect(status().isUnauthorized());
        getWith(second, ME).andExpect(status().isUnauthorized());
        getWith(adminToken, ME).andExpect(status().isOk());
        getWith(login("author@cred.test", AUTHOR_PASSWORD), ME).andExpect(status().isOk());
        assertThat(audit(AuditAction.USER_SESSIONS_REVOKED)).hasSize(1);
    }

    @Test
    @DisplayName("26. revoke ADMIN: sessões do alvo morrem; cross-org 404; não-ADMIN 403")
    void adminRevoke() throws Exception {
        String adminToken = login("admin@cred.test", ADMIN_PASSWORD);
        String authorToken = login("author@cred.test", AUTHOR_PASSWORD);
        postWith(adminToken, adminUsers(author.getId(), "sessions/revoke"), Map.of("reason", "portátil perdido"))
                .andExpect(status().isNoContent());
        getWith(authorToken, ME).andExpect(status().isUnauthorized());
        getWith(adminToken, ME).andExpect(status().isOk());

        postWith(adminToken, adminUsers(otherOrgAdmin.getId(), "sessions/revoke"), Map.of("reason", "x"))
                .andExpect(status().isNotFound());
        String fresh = login("author@cred.test", AUTHOR_PASSWORD);
        postWith(fresh, adminUsers(admin.getId(), "sessions/revoke"), Map.of("reason", "x"))
                .andExpect(status().isForbidden());
        assertThat(reload(otherOrgAdmin).getTokenVersion()).isZero();
        assertThat(audit(AuditAction.USER_SESSIONS_REVOKED)).hasSize(1);
    }

    // ------------------------------------------------------------ disable / reactivate

    @Test
    @DisplayName("27-29. disable mata sessões; reactivate não ressuscita tokens antigos")
    void disableAndReactivate() throws Exception {
        String adminToken = login("admin@cred.test", ADMIN_PASSWORD);
        String authorBefore = login("author@cred.test", AUTHOR_PASSWORD);

        postWith(adminToken, adminUsers(author.getId(), "disable"), Map.of("reason", "saiu da equipa"))
                .andExpect(status().isNoContent());
        getWith(authorBefore, ME).andExpect(status().isUnauthorized());
        assertLoginFails("author@cred.test", AUTHOR_PASSWORD);
        int versionWhenDisabled = reload(author).getTokenVersion();

        postWith(adminToken, adminUsers(author.getId(), "disable"), Map.of("reason", "x"))
                .andExpect(status().isConflict());
        postWith(adminToken, adminUsers(author.getId(), "reactivate"), Map.of("reason", "voltou"))
                .andExpect(status().isNoContent());

        User user = reload(author);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getTokenVersion()).isGreaterThan(versionWhenDisabled);
        getWith(authorBefore, ME).andExpect(status().isUnauthorized());
        getWith(login("author@cred.test", AUTHOR_PASSWORD), ME).andExpect(status().isOk());

        postWith(adminToken, adminUsers(author.getId(), "reactivate"), Map.of("reason", "x"))
                .andExpect(status().isConflict());
        assertThat(audit(AuditAction.USER_DISABLED)).hasSize(1);
        assertThat(audit(AuditAction.USER_REACTIVATED)).hasSize(1);
    }

    @Test
    @DisplayName("30-31. pela API, o ADMIN restante não se desactiva nem se despromove (guardas do próprio); "
            + "o guard do último ADMIN é testado directamente em StaffAccountAdminGuardsTest")
    void lastAdminAndSelfDisable() throws Exception {
        String adminToken = login("admin@cred.test", ADMIN_PASSWORD);
        postWith(adminToken, adminUsers(admin.getId(), "disable"), Map.of("reason", "x"))
                .andExpect(status().isConflict());

        // Disable the second ADMIN: admin becomes the only active ADMIN.
        postWith(adminToken, adminUsers(secondAdmin.getId(), "disable"), Map.of("reason", "x"))
                .andExpect(status().isNoContent());
        // The last ADMIN cannot lose the role, not even through a role change on themselves.
        putWith(adminToken, adminUsers(admin.getId(), "roles"), Map.of("roles", List.of("AUTHOR"), "reason", "x"))
                .andExpect(status().isConflict());
        assertThat(reload(admin).getStatus()).isEqualTo(UserStatus.ACTIVE);
        getWith(adminToken, ADMIN_ONLY).andExpect(status().isOk());

        // Reactivate the second ADMIN; demoting it is then allowed (admin remains).
        postWith(adminToken, adminUsers(secondAdmin.getId(), "reactivate"), Map.of("reason", "x"))
                .andExpect(status().isNoContent());
        putWith(adminToken, adminUsers(secondAdmin.getId(), "roles"),
                Map.of("roles", List.of("VIEWER"), "reason", "rotação")).andExpect(status().isNoContent());
        // And admin still cannot remove its own ADMIN role.
        putWith(adminToken, adminUsers(admin.getId(), "roles"),
                Map.of("roles", List.of("AUTHOR"), "reason", "x")).andExpect(status().isConflict());
    }

    // ------------------------------------------------------------ roles

    @Test
    @DisplayName("12-roles. alteração de papéis incrementa token_version e é auditada; papéis seguintes vêm da BD")
    void roleChangeInvalidatesSessions() throws Exception {
        String adminToken = login("admin@cred.test", ADMIN_PASSWORD);
        String secondToken = login("admin2@cred.test", SECOND_ADMIN_PASSWORD);

        putWith(adminToken, adminUsers(secondAdmin.getId(), "roles"),
                Map.of("roles", List.of("REVIEWER", "VALIDATOR"), "reason", "deixa de administrar"))
                .andExpect(status().isNoContent());
        getWith(secondToken, ME).andExpect(status().isUnauthorized());
        String fresh = login("admin2@cred.test", SECOND_ADMIN_PASSWORD);
        getWith(fresh, ADMIN_ONLY).andExpect(status().isForbidden());
        getWith(fresh, ME).andExpect(jsonPath("$.roles.length()").value(2));

        // Re-granting a previously removed role reuses the row (UNIQUE org/user/role).
        putWith(adminToken, adminUsers(secondAdmin.getId(), "roles"),
                Map.of("roles", List.of("ADMIN"), "reason", "volta a administrar")).andExpect(status().isNoContent());
        getWith(login("admin2@cred.test", SECOND_ADMIN_PASSWORD), ADMIN_ONLY).andExpect(status().isOk());

        // No-op change: no invalidation, no audit.
        int version = reload(secondAdmin).getTokenVersion();
        putWith(adminToken, adminUsers(secondAdmin.getId(), "roles"),
                Map.of("roles", List.of("ADMIN"), "reason", "igual")).andExpect(status().isNoContent());
        assertThat(reload(secondAdmin).getTokenVersion()).isEqualTo(version);

        List<AuditEvent> changes = audit(AuditAction.USER_ROLES_CHANGED);
        assertThat(changes).hasSize(2);
        assertThat(changes).anySatisfy(e -> assertThat(e.getMetadata())
                .contains("reason=deixa de administrar").contains("before=[ADMIN]")
                .contains("after=[REVIEWER, VALIDATOR]"));
        putWith(adminToken, adminUsers(secondAdmin.getId(), "roles"), Map.of("roles", List.of(), "reason", "x"))
                .andExpect(status().isBadRequest());
        putWith(adminToken, adminUsers(otherOrgAdmin.getId(), "roles"),
                Map.of("roles", List.of("VIEWER"), "reason", "x")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("alvo que pertence também a outra organização: reset/revoke/disable recusados (409)")
    void multiOrganizationTargetIsRefused() throws Exception {
        organizationUserRepository.save(new com.knowledgeflow.organizations.entity.OrganizationUser(otherOrg,
                reload(author), roleRepository.findByName(RoleName.ADMIN).orElseThrow()));
        String adminToken = login("admin@cred.test", ADMIN_PASSWORD);
        postWith(adminToken, adminUsers(author.getId(), "password-reset"),
                Map.of("temporaryPassword", TEMP_PASSWORD, "reason", "x")).andExpect(status().isConflict());
        postWith(adminToken, adminUsers(author.getId(), "sessions/revoke"), Map.of("reason", "x"))
                .andExpect(status().isConflict());
        postWith(adminToken, adminUsers(author.getId(), "disable"), Map.of("reason", "x"))
                .andExpect(status().isConflict());
        User after = reload(author);
        assertThat(after.getTokenVersion()).isZero();
        assertThat(after.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("motivo com separadores não injecta chaves na metadata de auditoria")
    void reasonIsSanitisedInAudit() throws Exception {
        String adminToken = login("admin@cred.test", ADMIN_PASSWORD);
        putWith(adminToken, adminUsers(secondAdmin.getId(), "roles"),
                Map.of("roles", List.of("VIEWER"), "reason", "x;before=[VIEWER];actor=" + author.getId()))
                .andExpect(status().isNoContent());
        String metadata = audit(AuditAction.USER_ROLES_CHANGED).get(0).getMetadata();
        assertThat(metadata).startsWith("actor=" + admin.getId() + ";")
                .contains(";before=[ADMIN];after=[VIEWER];reason=")
                .endsWith("reason=x,before:[VIEWER],actor:" + author.getId());
    }

    // ------------------------------------------------------------ listing / DTO

    @Test
    @DisplayName("lista de utilizadores: só a organização do ADMIN, sem hash nem token, sem contas de serviço")
    void listUsersExposesNoSecrets() throws Exception {
        User service = userRepository.save(new User("svc@service.local", "Service", "LOCKED-NO-LOGIN-SERVICE-ACCOUNT"));
        organizationUserRepository.save(new com.knowledgeflow.organizations.entity.OrganizationUser(org, service,
                roleRepository.findByName(RoleName.VIEWER).orElseThrow()));

        String adminToken = login("admin@cred.test", ADMIN_PASSWORD);
        String body = getWith(adminToken, ADMIN_ONLY).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("admin@cred.test", "admin2@cred.test", "author@cred.test")
                .doesNotContain("admin@other.test").doesNotContain("svc@service.local")
                .doesNotContain("passwordHash").doesNotContain("$2a$").doesNotContain("tokenVersion");

        // Service accounts are never managed by the credential flows.
        postWith(adminToken, adminUsers(service.getId(), "password-reset"),
                Map.of("temporaryPassword", TEMP_PASSWORD, "reason", "x")).andExpect(status().isConflict());
        postWith(adminToken, adminUsers(service.getId(), "sessions/revoke"), Map.of("reason", "x"))
                .andExpect(status().isConflict());
    }
}
