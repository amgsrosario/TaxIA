package com.knowledgeflow.cases.repository;

import com.knowledgeflow.cases.entity.KnowledgeCase;
import com.knowledgeflow.cases.enums.KnowledgeCaseStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeCaseRepository extends JpaRepository<KnowledgeCase, UUID> {

    @EntityGraph(attributePaths = {"organization", "client"})
    Optional<KnowledgeCase> findByIdAndOrganizationIdAndDeletedAtIsNull(UUID id, UUID organizationId);

    @EntityGraph(attributePaths = {"organization", "client"})
    Page<KnowledgeCase> findByOrganizationIdAndDeletedAtIsNull(UUID organizationId, Pageable pageable);

    @EntityGraph(attributePaths = {"organization", "client"})
    Page<KnowledgeCase> findByOrganizationIdAndStatusAndDeletedAtIsNull(
            UUID organizationId,
            KnowledgeCaseStatus status,
            Pageable pageable
    );
}
