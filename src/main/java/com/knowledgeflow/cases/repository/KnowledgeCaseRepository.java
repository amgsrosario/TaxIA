package com.knowledgeflow.cases.repository;

import com.knowledgeflow.cases.entity.KnowledgeCase;
import com.knowledgeflow.cases.enums.KnowledgeCaseStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Query(value = """
            SELECT * FROM knowledge_cases kc
            WHERE kc.organization_id = :organizationId
              AND kc.status = 'VALIDATED'
              AND kc.deleted_at IS NULL
              AND NOT EXISTS (
                SELECT 1 FROM knowledge_case_embeddings e WHERE e.knowledge_case_id = kc.id
              )
            """, nativeQuery = true)
    List<KnowledgeCase> findValidatedWithoutEmbedding(@Param("organizationId") UUID organizationId);

    @Query("SELECT kc FROM KnowledgeCase kc WHERE kc.organization.id = :organizationId AND kc.status = :status AND kc.deletedAt IS NULL")
    List<KnowledgeCase> findAllByOrganizationIdAndStatus(
            @Param("organizationId") UUID organizationId,
            @Param("status") KnowledgeCaseStatus status);

    // -------------------------------------------------------------------------
    // Portal — scoped to a specific client
    // -------------------------------------------------------------------------

    @EntityGraph(attributePaths = {"organization", "client"})
    Page<KnowledgeCase> findByClientIdAndOrganizationIdAndStatusAndDeletedAtIsNull(
            UUID clientId,
            UUID organizationId,
            KnowledgeCaseStatus status,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {"organization", "client"})
    Optional<KnowledgeCase> findByIdAndClientIdAndOrganizationIdAndStatusAndDeletedAtIsNull(
            UUID id,
            UUID clientId,
            UUID organizationId,
            KnowledgeCaseStatus status
    );
}
