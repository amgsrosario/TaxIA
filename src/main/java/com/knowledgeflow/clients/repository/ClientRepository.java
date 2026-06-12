package com.knowledgeflow.clients.repository;

import com.knowledgeflow.clients.entity.Client;
import com.knowledgeflow.clients.enums.ClientStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientRepository extends JpaRepository<Client, UUID> {

    @EntityGraph(attributePaths = "organization")
    Optional<Client> findByIdAndOrganizationIdAndDeletedAtIsNull(UUID id, UUID organizationId);

    @EntityGraph(attributePaths = "organization")
    Page<Client> findByOrganizationIdAndDeletedAtIsNull(UUID organizationId, Pageable pageable);

    @EntityGraph(attributePaths = "organization")
    Page<Client> findByOrganizationIdAndStatusAndDeletedAtIsNull(
            UUID organizationId,
            ClientStatus status,
            Pageable pageable
    );

    boolean existsByOrganizationIdAndTaxIdentifierAndDeletedAtIsNull(UUID organizationId, String taxIdentifier);
}
