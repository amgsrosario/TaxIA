package com.knowledgeflow.knowledge.service;

import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.service.AuditService;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.rag.KnowledgeQaEmbeddingIndexer;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes validated Q&A pairs to the RAG vector index.
 * <p>
 * Publication is only permitted when:
 * <ul>
 *   <li>curationStatus = VALIDATED</li>
 *   <li>at least one source reference exists</li>
 *   <li>validTo is absent or in the future</li>
 *   <li>for HIGH/CRITICAL risk: reviewedBy and reviewedAt must be set</li>
 * </ul>
 * Material changes to an already-published entry create a new version and
 * unpublish the old one, preserving history via previousVersionId.
 */
@Service
public class KnowledgeQuestionAnswerPublicationService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQuestionAnswerPublicationService.class);

    private final KnowledgeQuestionAnswerRepository qaRepository;
    private final KnowledgeSourceReferenceRepository sourceRepository;
    private final KnowledgeQaEmbeddingIndexer indexer;
    private final AuditService auditService;
    private final com.knowledgeflow.common.observability.KnowledgeFlowMetrics metrics;

    public KnowledgeQuestionAnswerPublicationService(
            KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeSourceReferenceRepository sourceRepository,
            KnowledgeQaEmbeddingIndexer indexer,
            AuditService auditService,
            com.knowledgeflow.common.observability.KnowledgeFlowMetrics metrics) {
        this.qaRepository = qaRepository;
        this.sourceRepository = sourceRepository;
        this.indexer = indexer;
        this.auditService = auditService;
        this.metrics = metrics;
    }

    // -------------------------------------------------------------------------
    // Publish
    // -------------------------------------------------------------------------

    @Transactional
    public void publish(UUID organizationId, UUID actingUserId, String publisherName, UUID id) {
        KnowledgeQuestionAnswer qa = requireOwned(organizationId, id);

        if (qa.isPublished()) {
            throw new BusinessException(ApiErrorCode.CONFLICT,
                    "Entry %s is already published".formatted(id));
        }

        assertEligible(qa);

        String activeAnswer = activeAnswer(qa);
        String topicLabel = qa.getTopic() != null ? qa.getTopic().name() : null;

        try {
            indexer.index(qa.getId(), qa.getOriginalQuestion(), activeAnswer, topicLabel);
        } catch (RuntimeException e) {
            metrics.recordPublication("embedding_failure");
            throw e;
        }
        qa.markPublished(publisherName);
        qaRepository.save(qa);

        auditService.record(organizationId, actingUserId,
                AuditAction.KNOWLEDGE_QA_PUBLISHED, "KnowledgeQuestionAnswer", id,
                "publisher=%s".formatted(publisherName));

        metrics.recordPublication("success");
        log.info("Published QA qaId={} org={}", id, organizationId);
    }

    // -------------------------------------------------------------------------
    // Reindex (idempotent reprocessing)
    // -------------------------------------------------------------------------

    /**
     * Rebuilds the embedding of an already-published entry. Idempotent: the
     * indexer upserts (ON CONFLICT DO UPDATE), so repeating never duplicates
     * embeddings, never creates a new version and never touches validated
     * content. Used to recover from an interrupted publication or a missing/
     * corrupted embedding without republishing.
     */
    @Transactional
    public void reindex(UUID organizationId, UUID actingUserId, UUID id) {
        KnowledgeQuestionAnswer qa = requireOwned(organizationId, id);

        if (!qa.isPublished()) {
            throw new BusinessException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "Only published entries can be reindexed — use publish for entry %s".formatted(id));
        }
        // Entradas publicadas antes desta regra podem não ter resposta técnica:
        // nunca regenerar embeddings para conteúdo sem technicalAnswer.
        if (qa.getTechnicalAnswer() == null || qa.getTechnicalAnswer().isBlank()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "Entry %s has no technicalAnswer — reindexing is blocked (unpublish and complete curation first)"
                            .formatted(id));
        }

        String topicLabel = qa.getTopic() != null ? qa.getTopic().name() : null;
        indexer.index(qa.getId(), qa.getOriginalQuestion(), activeAnswer(qa), topicLabel);

        auditService.record(organizationId, actingUserId,
                AuditAction.KNOWLEDGE_QA_REINDEXED, "KnowledgeQuestionAnswer", id);

        log.info("Reindexed QA qaId={} org={}", id, organizationId);
    }

    // -------------------------------------------------------------------------
    // Unpublish
    // -------------------------------------------------------------------------

    @Transactional
    public void unpublish(UUID organizationId, UUID actingUserId, UUID id) {
        KnowledgeQuestionAnswer qa = requireOwned(organizationId, id);

        if (!qa.isPublished()) {
            throw new BusinessException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "Entry %s is not currently published".formatted(id));
        }

        indexer.remove(qa.getId());
        qa.markUnpublished();
        qaRepository.save(qa);

        auditService.record(organizationId, actingUserId,
                AuditAction.KNOWLEDGE_QA_UNPUBLISHED, "KnowledgeQuestionAnswer", id);

        log.info("Unpublished QA qaId={} org={}", id, organizationId);
    }

    // -------------------------------------------------------------------------
    // New version (when answer changes after publication)
    // -------------------------------------------------------------------------

    /**
     * Creates a new version of a published entry:
     * 1. Unpublishes the current entry.
     * 2. Creates a copy with updated content, linking back via previousVersionId.
     * 3. Publishes the new version immediately.
     *
     * This preserves the full history without overwriting validated content.
     */
    @Transactional
    public KnowledgeQuestionAnswer createNewVersion(
            UUID organizationId,
            UUID actingUserId,
            String publisherName,
            UUID previousId,
            String newTechnicalAnswer) {

        KnowledgeQuestionAnswer previous = requireOwned(organizationId, previousId);
        assertEligible(previous);

        String versionedKey = previous.getExternalKey() != null
                ? previous.getExternalKey() + "_v" + (previous.getVersion() + 1) : null;
        if (versionedKey != null
                && versionedKey.length() > KnowledgeQuestionAnswer.EXTERNAL_KEY_MAX_LENGTH) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "externalKey of entry %s is too long to receive the versioning suffix (max %d)"
                            .formatted(previousId, KnowledgeQuestionAnswer.EXTERNAL_KEY_MAX_LENGTH));
        }

        // Unpublish previous
        indexer.remove(previous.getId());
        previous.markUnpublished();
        qaRepository.save(previous);

        // Create new entry inheriting metadata from previous
        KnowledgeQuestionAnswer newVersion = new KnowledgeQuestionAnswer(
                previous.getOrganization(),
                previous.getOriginalQuestion(),
                previous.getOriginalAnswer(),
                previous.getSourceSystem(),
                versionedKey);

        newVersion.updateCuration(
                previous.getNormalizedQuestion(),
                previous.getShortAnswer(),
                newTechnicalAnswer,
                previous.getTopic(),
                previous.getSubtopic(),
                previous.getJurisdiction(),
                previous.getRiskLevel(),
                previous.isRequiresHumanValidation(),
                previous.getValidFrom(),
                previous.getValidTo(),
                previous.getNotes());
        newVersion.setPreviousVersionId(previousId);
        // New version starts as PENDING_REVIEW — requires explicit re-validation
        newVersion.markPendingReview();

        qaRepository.save(newVersion);

        auditService.record(organizationId, actingUserId,
                AuditAction.KNOWLEDGE_QA_VERSION_CREATED, "KnowledgeQuestionAnswer",
                newVersion.getId(),
                "previousVersionId=%s".formatted(previousId));

        log.info("Created new version qaId={} previousId={}", newVersion.getId(), previousId);
        return newVersion;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertEligible(KnowledgeQuestionAnswer qa) {
        if (!qa.isEligibleForRag()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "Entry %s is not eligible for RAG publication: %s"
                            .formatted(qa.getId(), eligibilityReason(qa)));
        }
        long sourceCount = sourceRepository.countByQuestionAnswerId(qa.getId());
        if (sourceCount == 0) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "At least one source reference is required for publication");
        }
    }

    private String activeAnswer(KnowledgeQuestionAnswer qa) {
        return qa.getTechnicalAnswer() != null ? qa.getTechnicalAnswer() : qa.getShortAnswer();
    }

    private String eligibilityReason(KnowledgeQuestionAnswer qa) {
        if (qa.getCurationStatus() != com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus.VALIDATED) {
            return "status is " + qa.getCurationStatus();
        }
        if (qa.getTechnicalAnswer() == null || qa.getTechnicalAnswer().isBlank()) {
            return "a technicalAnswer is required for publication (shortAnswer alone is never enough)";
        }
        if (qa.getValidTo() != null && qa.getValidTo().isBefore(java.time.LocalDate.now())) {
            return "validTo expired on " + qa.getValidTo();
        }
        if ((qa.getRiskLevel() == com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel.HIGH
                || qa.getRiskLevel() == com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel.CRITICAL)
                && (qa.getReviewedBy() == null || qa.getReviewedBy().isBlank())) {
            return "HIGH/CRITICAL risk requires reviewedBy";
        }
        return "unknown reason";
    }

    private KnowledgeQuestionAnswer requireOwned(UUID organizationId, UUID id) {
        KnowledgeQuestionAnswer qa = qaRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND,
                        "KnowledgeQuestionAnswer not found: " + id));
        if (!qa.getOrganization().getId().equals(organizationId)) {
            // Cross-organization access answers NOT_FOUND (identical to a missing id)
            // so the existence of another organization's records is never revealed.
            throw new BusinessException(ApiErrorCode.NOT_FOUND,
                    "KnowledgeQuestionAnswer not found: " + id);
        }
        return qa;
    }
}
