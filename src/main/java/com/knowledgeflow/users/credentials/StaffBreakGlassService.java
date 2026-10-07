package com.knowledgeflow.users.credentials;

import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.service.AuditService;
import com.knowledgeflow.config.pilot.PilotDatasourceGuard;
import com.knowledgeflow.organizations.entity.OrganizationUser;
import com.knowledgeflow.organizations.repository.OrganizationUserRepository;
import com.knowledgeflow.users.entity.User;
import com.knowledgeflow.users.enums.RoleName;
import com.knowledgeflow.users.enums.UserStatus;
import com.knowledgeflow.users.repository.UserRepository;
import com.knowledgeflow.users.repository.UserRowLock;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Governed break-glass recovery of a pilot ADMIN who lost access (ADR-006).
 *
 * <p>Inert {@link Service}: no runner, scheduler or endpoint; it acts only when
 * {@link StaffAdminRecoveryLauncher} calls it by hand. Fail-closed, one target:
 * <ul>
 *   <li>off unless {@code BREAK_GLASS_ENABLED=true} in the environment of that execution;</li>
 *   <li>the datasource must be {@code knowledgeflow_pilot} on a loopback host
 *       ({@link PilotDatasourceGuard#validate(DataSource)});</li>
 *   <li>exactly one existing, non-deleted, ACTIVE, non-service user with that email, holding an
 *       active ADMIN membership in exactly one organization — anything else is BLOCKED;</li>
 *   <li>the new password follows {@link StaffPasswordPolicy} and must match its confirmation.</li>
 * </ul>
 * On success the password is replaced by a temporary one ({@code must_change_password=true}),
 * token_version is incremented (every session ends) and {@code USER_BREAK_GLASS_RESET} is audited
 * with the technical actor {@value #ACTOR} (no user row). It never creates users and never uses
 * the bootstrap-admin path.
 */
@Service
public class StaffBreakGlassService {

    public static final String ACTOR = "break-glass";

    private final UserRepository userRepository;
    private final UserRowLock userRowLock;
    private final OrganizationUserRepository organizationUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final DataSource dataSource;

    public StaffBreakGlassService(UserRepository userRepository,
                                  UserRowLock userRowLock,
                                  OrganizationUserRepository organizationUserRepository,
                                  PasswordEncoder passwordEncoder,
                                  AuditService auditService,
                                  DataSource dataSource) {
        this.userRepository = userRepository;
        this.userRowLock = userRowLock;
        this.organizationUserRepository = organizationUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.dataSource = dataSource;
    }

    @Transactional
    public BreakGlassResult recover(boolean enabled, String email, String reason,
                                    String newPassword, String confirmation, String host) {
        if (!enabled) {
            return BreakGlassResult.blocked("break-glass is disabled (BREAK_GLASS_ENABLED is not true)");
        }
        try {
            PilotDatasourceGuard.validate(dataSource);
        } catch (IllegalStateException e) {
            return BreakGlassResult.blocked("datasource is not knowledgeflow_pilot on a loopback host");
        }
        if (email == null || email.isBlank()) {
            return BreakGlassResult.blocked("--email is required");
        }
        if (reason == null || reason.isBlank()) {
            return BreakGlassResult.blocked("--reason is required");
        }
        if (newPassword == null || !newPassword.equals(confirmation)) {
            return BreakGlassResult.blocked("password confirmation does not match");
        }

        List<UUID> matches = userRepository.findIdsByEmailIgnoreCase(email.strip());
        if (matches.size() != 1) {
            return BreakGlassResult.blocked(matches.isEmpty()
                    ? "no user with that email"
                    : "ambiguous email (" + matches.size() + " users)");
        }
        User user = userRowLock.lock(matches.get(0)).orElse(null);
        if (user == null || user.isDeleted()) {
            return BreakGlassResult.blocked("no user with that email");
        }
        if (StaffAccounts.isServiceAccount(user)) {
            return BreakGlassResult.blocked("service accounts cannot be recovered");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            return BreakGlassResult.blocked("user is not ACTIVE");
        }
        List<UUID> adminOrganizations = organizationUserRepository.findByUserIdAndDeletedAtIsNull(user.getId()).stream()
                .filter(m -> m.getRole().getName() == RoleName.ADMIN)
                .filter(m -> m.getOrganization().getDeletedAt() == null)
                .map(OrganizationUser::getOrganization)
                .map(o -> o.getId())
                .distinct()
                .toList();
        if (adminOrganizations.size() != 1) {
            return BreakGlassResult.blocked(adminOrganizations.isEmpty()
                    ? "user is not an active ADMIN"
                    : "user is ADMIN of more than one organization (ambiguous)");
        }
        var violation = StaffPasswordPolicy.violation(newPassword, user.getEmail());
        if (violation.isPresent()) {
            return BreakGlassResult.blocked("password policy: " + violation.get());
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            return BreakGlassResult.blocked("password policy: the new password must differ from the current one");
        }

        UUID organizationId = adminOrganizations.get(0);
        user.resetPassword(passwordEncoder.encode(newPassword));
        auditService.record(organizationId, null, AuditAction.USER_BREAK_GLASS_RESET, "User", user.getId(),
                CredentialAuditMetadata.of(ACTOR, user.getId(), organizationId, reason,
                        "host=" + CredentialAuditMetadata.sanitize(host == null ? "unknown" : host)));
        return BreakGlassResult.recovered(List.of(
                "user=" + user.getId(),
                "organization=" + organizationId,
                "must_change_password=true",
                "sessions=revoked"));
    }
}
