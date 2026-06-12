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

    public AuthService(
            OrganizationRepository organizationRepository,
            OrganizationUserRepository organizationUserRepository,
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.organizationRepository = organizationRepository;
        this.organizationUserRepository = organizationUserRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse bootstrapAdmin(BootstrapAdminRequest request) {
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

        return jwtService.issueToken(user, organization, List.of(RoleName.ADMIN.name()));
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
