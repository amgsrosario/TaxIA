package com.knowledgeflow.organizations.repository;

import com.knowledgeflow.organizations.entity.Organization;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByTaxIdentifierAndDeletedAtIsNull(String taxIdentifier);
}
