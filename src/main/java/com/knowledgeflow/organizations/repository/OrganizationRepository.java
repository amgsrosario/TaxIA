package com.knowledgeflow.organizations.repository;

import com.knowledgeflow.organizations.entity.Organization;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByTaxIdentifierAndDeletedAtIsNull(String taxIdentifier);

    /**
     * Organization with a write lock: serialises staff account administration inside one
     * organization (last-ADMIN guard). Lock order is always organization, then user.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Organization o where o.id = :id and o.deletedAt is null")
    Optional<Organization> findActiveByIdForUpdate(@Param("id") UUID id);
}
