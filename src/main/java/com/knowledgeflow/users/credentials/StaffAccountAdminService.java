package com.knowledgeflow.users.credentials;

import com.knowledgeflow.audit.enums.AuditAction;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN administration of staff accounts in the ADMIN's own organization (ADR-006).
 *
 * <p>Every mutating operation locks the organization and then the target user (always in that
 * order), re-checks under that lock that the actor is still an active ADMIN with a current
 * session, resolves the target
 * only through an active membership in the ADMIN's organization (otherwise 404 — existence in
 * another organization is never revealed), refuses targets that also belong to another
 * organization (password, status and token_version are global), refuses non-login
 * service accounts, increments the target's token_version and records an audit event with
 * actor, target, organization and reason. The ADMIN password reset never applies to an account
 * holding an active ADMIN role. The last active ADMIN of an organization can never be
 * disabled or lose the ADMIN role, and an ADMIN cannot disable, demote or reset themselves here.
 */
@Service
public class StaffAccountAdminService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationUserRepository organizationUserRepository;
    private final UserRepository userRepository;
    private final UserRowLock userRowLock;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public StaffAccountAdminService(OrganizationRepository organizationRepository,
                                    OrganizationUserRepository organizationUserRepository,
                                    UserRepository userRepository,
                                    UserRowLock userRowLock,
                                    RoleRepository roleRepository,
                                    PasswordEncoder passwordEncoder,
                                    AuditService auditService) {
        this.organizationRepository = organizationRepository;
        this.organizationUserRepository = organizationUserRepository;
        this.userRepository = userRepository;
        this.userRowLock = userRowLock;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<StaffUserSummary> listUsers(AuthenticatedUser actor) {
        Map<UUID, List<OrganizationUser>> byUser = organizationUserRepository
                .findByOrganizationIdAndDeletedAtIsNull(actor.organizationId()).stream()
                .filter(m -> !m.getUser().isDeleted())
                .collect(Collectors.groupingBy(m -> m.getUser().getId(), LinkedHashMap::new, Collectors.toList()));
        List<StaffUserSummary> result = new ArrayList<>();
        byUser.forEach((userId, memberships) -> {
            User user = memberships.get(0).getUser();
            if (StaffAccounts.isServiceAccount(user)) {
                return;
            }
            result.add(new StaffUserSummary(user.getId(), user.getEmail(), user.getFullName(), user.getStatus(),
                    memberships.stream().map(m -> m.getRole().getName().name()).distinct().sorted().toList(),
                    user.isMustChangePassword(), userId.equals(actor.userId())));
        });
        result.sort(Comparator.comparing(StaffUserSummary::email));
        return result;
    }

    @Transactional
    public void revokeSessions(AuthenticatedUser actor, long actorTokenVersion, UUID targetId, String reason) {
        Target target = lockTarget(actor, actorTokenVersion, targetId);
        target.user().revokeSessions();
        audit(actor, target, AuditAction.USER_SESSIONS_REVOKED, reason);
    }

    @Transactional
    public void resetPassword(AuthenticatedUser actor, long actorTokenVersion, UUID targetId, String temporaryPassword, String reason) {
        if (actor.userId().equals(targetId)) {
            throw conflict("Use a alteração da própria password, não a reposição por ADMIN.");
        }
        Target target = lockTarget(actor, actorTokenVersion, targetId);
        // ADR-006: an ADMIN account is never reset by another ADMIN (it could then act as that
        // ADMIN). An authenticated ADMIN uses the own password change; an ADMIN without access is
        // recovered only by the governed break-glass.
        if (target.roles().contains(RoleName.ADMIN)) {
            throw conflict("Contas ADMIN não podem ser repostas por esta via; usar a alteração da própria "
                    + "password ou o break-glass governado.");
        }
        User user = target.user();
        StaffPasswordPolicy.violation(temporaryPassword, user.getEmail()).ifPresent(message -> {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR, message);
        });
        if (passwordEncoder.matches(temporaryPassword, user.getPasswordHash())) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "A password temporária tem de ser diferente da actual.");
        }
        user.resetPassword(passwordEncoder.encode(temporaryPassword));
        audit(actor, target, AuditAction.USER_PASSWORD_RESET, reason);
    }

    @Transactional
    public void disable(AuthenticatedUser actor, long actorTokenVersion, UUID targetId, String reason) {
        if (actor.userId().equals(targetId)) {
            throw conflict("Um ADMIN não se pode desactivar a si próprio.");
        }
        Target target = lockTarget(actor, actorTokenVersion, targetId);
        User user = target.user();
        if (user.getStatus() == UserStatus.DISABLED) {
            throw conflict("O utilizador já está desactivado.");
        }
        if (target.roles().contains(RoleName.ADMIN)
                && organizationUserRepository.countOtherActiveAdmins(target.organization().getId(), user.getId()) == 0) {
            throw conflict("Não é possível desactivar o último ADMIN activo da organização.");
        }
        user.disable();
        audit(actor, target, AuditAction.USER_DISABLED, reason);
    }

    @Transactional
    public void reactivate(AuthenticatedUser actor, long actorTokenVersion, UUID targetId, String reason) {
        Target target = lockTarget(actor, actorTokenVersion, targetId);
        if (target.user().getStatus() == UserStatus.ACTIVE) {
            throw conflict("O utilizador já está activo.");
        }
        target.user().reactivate();
        audit(actor, target, AuditAction.USER_REACTIVATED, reason);
    }

    @Transactional
    public void changeRoles(AuthenticatedUser actor, long actorTokenVersion, UUID targetId, Set<RoleName> requested, String reason) {
        if (requested == null || requested.isEmpty()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR, "Indique pelo menos um papel.");
        }
        Set<RoleName> desired = EnumSet.copyOf(requested);
        Target target = lockTarget(actor, actorTokenVersion, targetId);
        User user = target.user();
        boolean losesAdmin = target.roles().contains(RoleName.ADMIN) && !desired.contains(RoleName.ADMIN);
        if (losesAdmin && actor.userId().equals(targetId)) {
            throw conflict("Um ADMIN não pode retirar o próprio papel ADMIN.");
        }
        if (losesAdmin && user.getStatus() == UserStatus.ACTIVE
                && organizationUserRepository.countOtherActiveAdmins(target.organization().getId(), user.getId()) == 0) {
            throw conflict("Não é possível retirar o papel ADMIN ao último ADMIN activo da organização.");
        }
        // ADR-006: never grant ADMIN while an ADMIN-set temporary password is pending, otherwise
        // demote → reset → re-grant would hand the resetting ADMIN a password-known ADMIN account.
        if (desired.contains(RoleName.ADMIN) && !target.roles().contains(RoleName.ADMIN)
                && user.isMustChangePassword()) {
            throw conflict("Não é possível conceder ADMIN enquanto a mudança de password obrigatória estiver "
                    + "pendente.");
        }
        if (desired.equals(target.roles())) {
            return; // nothing to change: no token invalidation, no audit
        }

        List<OrganizationUser> rows = organizationUserRepository
                .findByOrganizationIdAndUserId(target.organization().getId(), user.getId());
        for (RoleName roleName : desired) {
            OrganizationUser existing = rows.stream()
                    .filter(m -> m.getRole().getName() == roleName).findFirst().orElse(null);
            if (existing == null) {
                Role role = roleRepository.findByName(roleName).orElseThrow(() ->
                        new BusinessException(ApiErrorCode.INTERNAL_ERROR, "Role " + roleName + " is not seeded"));
                organizationUserRepository.save(new OrganizationUser(target.organization(), user, role));
            } else if (!existing.isActive()) {
                existing.restore();
            }
        }
        for (OrganizationUser row : rows) {
            if (row.isActive() && !desired.contains(row.getRole().getName())) {
                row.softDelete();
            }
        }
        user.revokeSessions();
        auditService.record(target.organization().getId(), actor.userId(), AuditAction.USER_ROLES_CHANGED,
                "User", user.getId(),
                CredentialAuditMetadata.of(actor.userId().toString(), user.getId(), target.organization().getId(),
                        reason, "before=" + sorted(target.roles()), "after=" + sorted(desired)));
    }

    // -------------------------------------------------------------------------

    private record Target(Organization organization, User user, Set<RoleName> roles) {
    }

    /** Organization lock, then user lock; the target must be an active member of the ADMIN's organization. */
    private Target lockTarget(AuthenticatedUser actor, long actorTokenVersion, UUID targetId) {
        Organization organization = organizationRepository.findActiveByIdForUpdate(actor.organizationId())
                .orElseThrow(StaffAccountAdminService::notFound);
        // Re-check the actor under the organization lock: an ADMIN disabled or demoted by a
        // concurrent operation cannot complete an operation that started before.
        if (!organizationUserRepository.isActiveAdmin(organization.getId(), actor.userId())) {
            throw new BusinessException(ApiErrorCode.FORBIDDEN, "Sem permissões para esta operação.");
        }
        // …and the actor's own session must still be current (revoked/reset meanwhile → 401).
        if (userRepository.findTokenVersionById(actor.userId()).map(v -> v != actorTokenVersion).orElse(true)) {
            throw new BusinessException(ApiErrorCode.UNAUTHORIZED, "Sessão inválida ou revogada.");
        }
        if (!organizationUserRepository.existsByOrganizationIdAndUserIdAndDeletedAtIsNull(
                organization.getId(), targetId)) {
            throw notFound();
        }
        User user = userRowLock.lock(targetId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(StaffAccountAdminService::notFound);
        if (StaffAccounts.isServiceAccount(user)) {
            throw conflict("Contas de serviço não são geridas por esta operação.");
        }
        // Password, status and token_version are global to the user: an ADMIN of one organization
        // must never change them for someone who also belongs to another organization.
        if (organizationUserRepository.existsByUserIdAndOrganizationIdNotAndDeletedAtIsNull(
                targetId, organization.getId())) {
            throw conflict("Operação não permitida para este utilizador.");
        }
        Set<RoleName> roles = EnumSet.noneOf(RoleName.class);
        organizationUserRepository.findByOrganizationIdAndUserId(organization.getId(), targetId).stream()
                .filter(OrganizationUser::isActive)
                .forEach(m -> roles.add(m.getRole().getName()));
        return new Target(organization, user, roles);
    }

    private void audit(AuthenticatedUser actor, Target target, AuditAction action, String reason) {
        auditService.record(target.organization().getId(), actor.userId(), action, "User", target.user().getId(),
                CredentialAuditMetadata.of(actor.userId().toString(), target.user().getId(),
                        target.organization().getId(), reason));
    }

    private static String sorted(Set<RoleName> roles) {
        return roles.stream().map(Enum::name).sorted().toList().toString();
    }

    private static BusinessException notFound() {
        return new BusinessException(ApiErrorCode.NOT_FOUND, "Utilizador não encontrado.");
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ApiErrorCode.CONFLICT, message);
    }
}
