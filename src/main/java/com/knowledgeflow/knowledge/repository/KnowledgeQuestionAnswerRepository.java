package com.knowledgeflow.knowledge.repository;

import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeQuestionAnswerRepository extends JpaRepository<KnowledgeQuestionAnswer, UUID> {

    /** Idempotency check: find by source system + external key within an organization. */
    Optional<KnowledgeQuestionAnswer> findByOrganizationIdAndSourceSystemAndExternalKey(
            UUID organizationId, String sourceSystem, String externalKey);

    /**
     * Locate a single Q&A by source system + external key across all organizations. Used only by
     * the guarded one-shot pilot runner (Bloco E, E9C), which resolves an explicit externalKey and
     * enforces an exactly-one match (N=1). It is intentionally organization-agnostic so the runner
     * can fail closed — refusing to act — whenever the key is missing or ambiguous.
     */
    List<KnowledgeQuestionAnswer> findBySourceSystemAndExternalKey(
            String sourceSystem, String externalKey);

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

    /**
     * Campos de âmbito (tema, subtema, pergunta) de várias Q&amp;A numa única query, como linhas só de
     * leitura — sem carregar entidades. Usado pelo gate de âmbito fiscal (M4-SCOPE).
     */
    /**
     * Campos de âmbito (M4-SCOPE) das Q&amp;A pedidas, numa só query. Sem filtro de organização: os
     * ids vêm do RAG, que já está restrito à organização do pedido.
     */
    @Query("""
            select new com.knowledgeflow.knowledge.repository.KnowledgeQaScopeRow(
                q.id, q.topic, q.subtopic, q.originalQuestion, q.normalizedQuestion)
            from KnowledgeQuestionAnswer q
            where q.id in :ids
            """)
    List<KnowledgeQaScopeRow> findScopeRowsByIdIn(@Param("ids") Collection<UUID> ids);

    /**
     * Q&amp;A com lock de escrita, para serializar as alterações de exclusões de aplicabilidade com
     * a publicação (que actualiza a mesma linha).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from KnowledgeQuestionAnswer q where q.id = :id")
    Optional<KnowledgeQuestionAnswer> findByIdForUpdate(@Param("id") UUID id);

    /** previous_version_id de uma Q&amp;A, sem carregar a entidade (para percorrer a linhagem antes dos locks). */
    @Query("select q.previousVersionId from KnowledgeQuestionAnswer q where q.id = :id")
    Optional<UUID> findPreviousVersionIdById(@Param("id") UUID id);

    /** Versões derivadas directamente de uma Q&amp;A (linhagem por previous_version_id). */
    List<KnowledgeQuestionAnswer> findByPreviousVersionId(UUID previousVersionId);

    /** Chaves externas da mesma família de versões (base e base_vN), para numerar a próxima. */
    @Query("""
            select q.externalKey from KnowledgeQuestionAnswer q
            where q.organization.id = :organizationId
              and ((:sourceSystem is null and q.sourceSystem is null) or q.sourceSystem = :sourceSystem)
              and (q.externalKey = :baseKey or q.externalKey like concat(:baseKey, '\\_v%') escape '\\')
            """)
    List<String> findVersionKeys(@Param("organizationId") UUID organizationId,
            @Param("sourceSystem") String sourceSystem, @Param("baseKey") String baseKey);
}
