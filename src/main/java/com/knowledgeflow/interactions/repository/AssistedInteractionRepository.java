package com.knowledgeflow.interactions.repository;

import com.knowledgeflow.interactions.entity.AssistedInteraction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssistedInteractionRepository extends JpaRepository<AssistedInteraction, UUID> {

    Optional<AssistedInteraction> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Page<AssistedInteraction> findByOrganizationIdAndClientId(UUID organizationId, UUID clientId, Pageable pageable);

    Page<AssistedInteraction> findByOrganizationId(UUID organizationId, Pageable pageable);

    // Portal — must scope to the authenticated client
    Optional<AssistedInteraction> findByIdAndOrganizationIdAndClientId(UUID id, UUID organizationId, UUID clientId);

    Page<AssistedInteraction> findByOrganizationIdAndClientIdOrderByCreatedAtDesc(
            UUID organizationId, UUID clientId, Pageable pageable);
}
