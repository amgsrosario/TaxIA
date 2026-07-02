package com.knowledgeflow.auth.service;

import com.knowledgeflow.auth.dto.AuthResponse;
import com.knowledgeflow.auth.dto.BootstrapAdminRequest;
import com.knowledgeflow.auth.dto.LoginRequest;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.entity.OrganizationUser;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.organizations.repository.OrganizationUserRepository;
import com.knowledgeflow.users.entity.Role;
import com.knowledgeflow.users.entity.User;
import com.knowledgeflow.users.enums.RoleName;
import com.knowledgeflow.users.enums.UserStatus;
import com.knowledgeflow.users.repository.RoleRepository;
import com.knowledgeflow.users.repository.UserRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationUserRepository organizationUserRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final com.knowledgeflow.auth.AuthBootstrapProperties bootstrapProperties;
    private final com.knowledgeflow.audit.service.AuditService auditService;

    public AuthService(
            OrganizationRepository organizationRepository,
            OrganizationUserRepository organizationUserRepository,
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            com.knowledgeflow.auth.AuthBootstrapProperties bootstrapProperties,
            com.knowledgeflow.audit.service.AuditService auditService
    ) {
        this.organizationRepository = organizationRepository;
        this.organizationUserRepository = organizationUserRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.bootstrapProperties = bootstrapProperties;
        this.auditService = auditService;
    }

    /**
     * One-shot bootstrap: creates the first organization + ADMIN user.
     * Security gates, in order:
     *  1. disabled by default — responds NOT_FOUND so the endpoint is invisible;
     *  2. requires a secret from the environment (BOOTSTRAP_ADMIN_SECRET);
     *  3. constant-time secret comparison;
     *  4. single-use — blocked as soon as any user exists;
     *  5. audited (ADMIN_BOOTSTRAPPED).
     */
    @Transactional
    public AuthResponse bootstrapAdmin(BootstrapAdminRequest request, String providedSecret) {
        if (!bootstrapProperties.enabled()) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "Resource was not found");
        }
        if (!bootstrapProperties.hasSecret()) {
            throw new BusinessException(ApiErrorCode.FORBIDDEN,
                    "Bootstrap secret is not configured — set BOOTSTRAP_ADMIN_SECRET");
        }
        if (providedSecret == null || !constantTimeEquals(bootstrapProperties.secret(), providedSecret)) {
            throw new BusinessException(ApiErrorCode.FORBIDDEN, "Invalid bootstrap secret");
        }
        if (userRepository.count() > 0) {
            throw new BusinessException(ApiErrorCode.CONFLICT, "Bootstrap admin can only be used before users exist");
        }
        if (userRepository.existsByEmailIgnoreCaseAndDeletedAtIsNull(request.adminEmail())) {
            throw new BusinessException(ApiErrorCode.CONFLICT, "A user with this email already exists");
        }
        if (request.taxIdentifier() != null
                && !request.taxIdentifier().isBlank()
                && organizationRepository.existsByTaxIdentifierAndDeletedAtIsNull(request.taxIdentifier())) {
            throw new BusinessException(ApiErrorCode.CONFLICT, "An organization with this tax identifier already exists");
        }

        Organization organization = organizationRepository.save(new Organization(
                request.organizationName(),
                normalizeBlank(request.taxIdentifier())
        ));
        User user = userRepository.save(new User(
                request.adminEmail().toLowerCase(),
                request.adminFullName(),
                passwordEncoder.encode(request.adminPassword())
        ));
        Role adminRole = roleRepository.findByName(RoleName.ADMIN)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.INTERNAL_ERROR, "ADMIN role is not seeded"));
        organizationUserRepository.save(new OrganizationUser(organization, user, adminRole));

        auditService.record(organization.getId(), user.getId(),
                com.knowledgeflow.audit.enums.AuditAction.ADMIN_BOOTSTRAPPED,
                "User", user.getId(),
                "organization=%s".formatted(organization.getName()));

        return jwtService.issueToken(user, organization, List.of(RoleName.ADMIN.name()));
    }

    /** Constant-time comparison — never leaks secret length/prefix via timing. */
    private static boolean constantTimeEquals(String expected, String provided) {
        byte[] a = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = provided.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(a, b);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        OrganizationUser membership = organizationUserRepository
                .findFirstByUserEmailIgnoreCaseAndDeletedAtIsNull(request.email())
                .orElseThrow(() -> invalidCredentials());

        User user = membership.getUser();
        if (user.getStatus() != UserStatus.ACTIVE || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }

        List<String> roles = organizationUserRepository.findByUserIdAndDeletedAtIsNull(user.getId()).stream()
                .filter(item -> item.getOrganization().getId().equals(membership.getOrganization().getId()))
                .map(item -> item.getRole().getName().name())
                .sorted(Comparator.naturalOrder())
                .toList();

        return jwtService.issueToken(user, membership.getOrganization(), roles);
    }

    private BusinessException invalidCredentials() {
        return new BusinessException(ApiErrorCode.UNAUTHORIZED, "Invalid email or password");
    }

    private String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
