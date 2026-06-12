package com.knowledgeflow.organizations.repository;

import com.knowledgeflow.organizations.entity.OrganizationUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationUserRepository extends JpaRepository<OrganizationUser, UUID> {

    @EntityGraph(attributePaths = {"organization", "user", "role"})
    List<OrganizationUser> findByUserIdAndDeletedAtIsNull(UUID userId);

    @EntityGraph(attributePaths = {"organization", "user", "role"})
    Optional<OrganizationUser> findFirstByUserEmailIgnoreCaseAndDeletedAtIsNull(String email);
}
