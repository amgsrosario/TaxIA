package com.knowledgeflow.knowledge.repository;

import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeQuestionAnswerRepository extends JpaRepository<KnowledgeQuestionAnswer, UUID> {

    /** Idempotency check: find by source system + external key within an organization. */
    Optional<KnowledgeQuestionAnswer> findByOrganizationIdAndSourceSystemAndExternalKey(
            UUID organizationId, String sourceSystem, String externalKey);

    /** Detect exact question duplicates within an organization. */
    List<KnowledgeQuestionAnswer> findByOrganizationIdAndOriginalQuestion(
            UUID organizationId, String originalQuestion);

    /** Detect exact answer duplicates: same question AND same answer. */
    List<KnowledgeQuestionAnswer> findByOrganizationIdAndOriginalQuestionAndOriginalAnswer(
            UUID organizationId, String originalQuestion, String originalAnswer);

    /** Filtered list for admin UI. */
    Page<KnowledgeQuestionAnswer> findByOrganizationIdAndCurationStatus(
            UUID organizationId, KnowledgeCurationStatus status, Pageable pageable);

    Page<KnowledgeQuestionAnswer> findByOrganizationIdAndCurationStatusAndTopic(
            UUID organizationId, KnowledgeCurationStatus status, KnowledgeTopic topic, Pageable pageable);

    Page<KnowledgeQuestionAnswer> findByOrganizationId(UUID organizationId, Pageable pageable);

    Page<KnowledgeQuestionAnswer> findByOrganizationIdAndTopic(
            UUID organizationId, KnowledgeTopic topic, Pageable pageable);

    /** Active validated entries for RAG eligibility check. */
    @Query("""
            SELECT qa FROM KnowledgeQuestionAnswer qa
            WHERE qa.organization.id = :orgId
              AND qa.curationStatus = 'VALIDATED'
              AND (qa.validTo IS NULL OR qa.validTo >= :today)
            """)
    List<KnowledgeQuestionAnswer> findValidatedActiveForOrg(
            @Param("orgId") UUID orgId,
            @Param("today") LocalDate today);

    /** Find published entries that have not yet been indexed (publishedAt set but no embedding yet). */
    @Query("""
            SELECT qa FROM KnowledgeQuestionAnswer qa
            WHERE qa.organization.id = :orgId
              AND qa.curationStatus = 'VALIDATED'
              AND qa.publishedAt IS NOT NULL
            """)
    List<KnowledgeQuestionAnswer> findPublishedForOrg(@Param("orgId") UUID orgId);

    /** Check for canonical conflict: another VALIDATED canonical entry with same topic. */
    @Query("""
            SELECT qa FROM KnowledgeQuestionAnswer qa
            WHERE qa.organization.id = :orgId
              AND qa.topic = :topic
              AND qa.canonical = true
              AND qa.curationStatus = 'VALIDATED'
              AND qa.id <> :excludeId
            """)
    List<KnowledgeQuestionAnswer> findCanonicalConflicts(
            @Param("orgId") UUID orgId,
            @Param("topic") KnowledgeTopic topic,
            @Param("excludeId") UUID excludeId);

    long countByOrganizationIdAndCurationStatus(UUID organizationId, KnowledgeCurationStatus status);
}
