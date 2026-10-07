package com.knowledgeflow.users.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgeflow.audit.service.AuditService;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.entity.OrganizationUser;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.organizations.repository.OrganizationUserRepository;
import com.knowledgeflow.security.AuthenticatedUser;
import com.knowledgeflow.users.entity.Role;
import com.knowledgeflow.users.entity.User;
import com.knowledgeflow.users.enums.RoleName;
import com.knowledgeflow.users.enums.UserStatus;
import com.knowledgeflow.users.repository.RoleRepository;
import com.knowledgeflow.users.repository.UserRepository;
import com.knowledgeflow.users.repository.UserRowLock;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * ADR-006 guards of the ADMIN service at unit level. The last-ADMIN guard is defence in depth:
 * through the API the actor is always another active ADMIN, so the self guards and the actor
 * re-check fire first. These tests reach the guard directly by making the repository report no
 * other active ADMIN.
 */
class StaffAccountAdminGuardsTest {

    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyzABCDE";

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final OrganizationUserRepository memberships = mock(OrganizationUserRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final UserRowLock rowLock = mock(UserRowLock.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final StaffAccountAdminService service = new StaffAccountAdminService(
            organizations, memberships, users, rowLock, roles, encoder, audit);

    private final UUID orgId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();
    private final AuthenticatedUser actor = new AuthenticatedUser(actorId, orgId, "a@x.test", List.of("ADMIN"));
    private User target;

    @BeforeEach
    void setUp() throws Exception {
        Organization org = mock(Organization.class);
        when(org.getId()).thenReturn(orgId);
        when(organizations.findActiveByIdForUpdate(orgId)).thenReturn(Optional.of(org));
        when(memberships.isActiveAdmin(orgId, actorId)).thenReturn(true);
        when(users.findTokenVersionById(actorId)).thenReturn(Optional.of(3));
        when(memberships.existsByOrganizationIdAndUserIdAndDeletedAtIsNull(orgId, targetId)).thenReturn(true);
        target = new User("t@x.test", "Target", HASH);
        when(rowLock.lock(targetId)).thenReturn(Optional.of(target));
        Role admin = new Role(RoleName.ADMIN, "ADMIN");
        OrganizationUser membership = mock(OrganizationUser.class);
        when(membership.getRole()).thenReturn(admin);
        when(membership.isActive()).thenReturn(true);
        when(memberships.findByOrganizationIdAndUserId(orgId, targetId)).thenReturn(List.of(membership));
    }

    private void assertCode(Runnable call, ApiErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @Test
    void lastActiveAdminCannotBeDisabledOrDemoted() {
        when(memberships.countOtherActiveAdmins(orgId, targetId)).thenReturn(0L);
        assertCode(() -> service.disable(actor, 3, targetId, "r"), ApiErrorCode.CONFLICT);
        assertCode(() -> service.changeRoles(actor, 3, targetId, EnumSet.of(RoleName.VIEWER), "r"),
                ApiErrorCode.CONFLICT);
        assertThat(target.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(target.getTokenVersion()).isZero();
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void adminAccountIsNeverResetByAnotherAdmin() {
        // setUp gives the target an active ADMIN membership; another ADMIN may not reset it. The
        // encoder returns a valid hash, so without the guard the reset would succeed (no 409).
        when(encoder.encode(any())).thenReturn("$2a$10$zyxwvutsrqponmlkjihgfedcbazyxwvutsrqponmlkjihgfedcbaZ");
        assertCode(() -> service.resetPassword(actor, 3, targetId, "temporaria-valida-1", "r"), ApiErrorCode.CONFLICT);
        assertThat(target.isMustChangePassword()).isFalse();
        assertThat(target.getTokenVersion()).isZero();
        assertThat(target.getPasswordHash()).isEqualTo(HASH);
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void adminIsNotGrantedWhileTemporaryPasswordIsPending() {
        OrganizationUser viewer = mock(OrganizationUser.class);
        when(viewer.getRole()).thenReturn(new Role(RoleName.VIEWER, "VIEWER"));
        when(viewer.isActive()).thenReturn(true);
        when(memberships.findByOrganizationIdAndUserId(orgId, targetId)).thenReturn(List.of(viewer));
        when(roles.findByName(RoleName.ADMIN)).thenReturn(Optional.of(new Role(RoleName.ADMIN, "ADMIN")));
        target.resetPassword("$2a$10$tttttttttttttttttttttuabcdefghijklmnopqrstuvwxyzABCDE");
        int version = target.getTokenVersion();

        assertCode(() -> service.changeRoles(actor, 3, targetId, EnumSet.of(RoleName.ADMIN), "r"), ApiErrorCode.CONFLICT);
        assertThat(target.getTokenVersion()).isEqualTo(version);
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());

        // After the holder sets the final password, the same grant goes through.
        target.changePassword("$2a$10$ffffffffffffffffffffffabcdefghijklmnopqrstuvwxyzABCDE");
        service.changeRoles(actor, 3, targetId, EnumSet.of(RoleName.ADMIN), "r");
        assertThat(target.getTokenVersion()).isEqualTo(version + 2);
    }

    @Test
    void staleActorSessionIsRefusedUnderTheLock() {
        assertCode(() -> service.revokeSessions(actor, 2, targetId, "r"), ApiErrorCode.UNAUTHORIZED);
        assertThat(target.getTokenVersion()).isZero();
    }

    @Test
    void actorNoLongerAdminIsRefused() {
        when(memberships.isActiveAdmin(orgId, actorId)).thenReturn(false);
        assertCode(() -> service.revokeSessions(actor, 3, targetId, "r"), ApiErrorCode.FORBIDDEN);
    }

    @Test
    void targetInAnotherOrganizationToo_isRefused() {
        when(memberships.existsByUserIdAndOrganizationIdNotAndDeletedAtIsNull(targetId, orgId)).thenReturn(true);
        assertCode(() -> service.revokeSessions(actor, 3, targetId, "r"), ApiErrorCode.CONFLICT);
        assertCode(() -> service.disable(actor, 3, targetId, "r"), ApiErrorCode.CONFLICT);
        assertThat(target.getTokenVersion()).isZero();
    }

    @Test
    void reasonCannotInjectAuditKeys() {
        String metadata = CredentialAuditMetadata.of("actor-1", targetId, orgId,
                "x;before=[VIEWER];after=[VIEWER];actor=evil\nnext", "before=[ADMIN]", "after=[AUTHOR]");
        assertThat(metadata).endsWith("reason=x,before:[VIEWER],after:[VIEWER],actor:evil next");
        assertThat(metadata.split(";")).hasSize(6);
        assertThat(metadata).startsWith("actor=actor-1;");
    }
}
