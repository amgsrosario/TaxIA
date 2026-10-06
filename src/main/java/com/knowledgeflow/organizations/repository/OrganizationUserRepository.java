package com.knowledgeflow.organizations.repository;

import com.knowledgeflow.organizations.entity.OrganizationUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationUserRepository extends JpaRepository<OrganizationUser, UUID> {

    @EntityGraph(attributePaths = {"organization", "user", "role"})
    List<OrganizationUser> findByUserIdAndDeletedAtIsNull(UUID userId);

    @EntityGraph(attributePaths = {"organization", "user", "role"})
    Optional<OrganizationUser> findFirstByUserEmailIgnoreCaseAndDeletedAtIsNull(String email);

    /** Every membership row (including removed ones) of a user in one organization. */
    @EntityGraph(attributePaths = {"organization", "user", "role"})
    List<OrganizationUser> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    /** Active memberships of an organization, for the ADMIN user list. */
    @EntityGraph(attributePaths = {"organization", "user", "role"})
    List<OrganizationUser> findByOrganizationIdAndDeletedAtIsNull(UUID organizationId);

    boolean existsByOrganizationIdAndUserIdAndDeletedAtIsNull(UUID organizationId, UUID userId);

    /** Whether the user also holds an active membership in another organization. */
    boolean existsByUserIdAndOrganizationIdNotAndDeletedAtIsNull(UUID userId, UUID organizationId);

    /**
     * Number of distinct users that hold an active ADMIN membership in the organization, are
     * ACTIVE and not deleted, excluding one user. Used by the last-ADMIN guard; callers hold the
     * organization lock so two concurrent removals cannot both pass.
     */
    @Query("""
            select count(distinct ou.user.id) from OrganizationUser ou
            where ou.organization.id = :organizationId
              and ou.deletedAt is null
              and ou.role.name = com.knowledgeflow.users.enums.RoleName.ADMIN
              and ou.user.status = com.knowledgeflow.users.enums.UserStatus.ACTIVE
              and ou.user.deletedAt is null
              and ou.user.id <> :excludedUserId
            """)
    long countOtherActiveAdmins(@Param("organizationId") UUID organizationId,
                                @Param("excludedUserId") UUID excludedUserId);

    /** Whether the user is, right now, an ACTIVE, non-deleted ADMIN of the organization. */
    @Query("""
            select case when count(ou) > 0 then true else false end from OrganizationUser ou
            where ou.organization.id = :organizationId
              and ou.user.id = :userId
              and ou.deletedAt is null
              and ou.role.name = com.knowledgeflow.users.enums.RoleName.ADMIN
              and ou.user.status = com.knowledgeflow.users.enums.UserStatus.ACTIVE
              and ou.user.deletedAt is null
            """)
    boolean isActiveAdmin(@Param("organizationId") UUID organizationId, @Param("userId") UUID userId);
}
