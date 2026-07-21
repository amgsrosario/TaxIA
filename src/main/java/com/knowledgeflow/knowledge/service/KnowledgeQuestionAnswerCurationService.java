package com.knowledgeflow.knowledge.service;

import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.service.AuditService;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.knowledge.dto.KnowledgeQaCurationRequest;
import com.knowledgeflow.knowledge.dto.KnowledgeQaDetailResponse;
import com.knowledgeflow.knowledge.dto.KnowledgeQaResponse;
import com.knowledgeflow.knowledge.dto.SourceReferenceRequest;
import com.knowledgeflow.knowledge.dto.SourceReferenceResponse;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeQuestionAnswerCurationService {

    private final KnowledgeQuestionAnswerRepository qaRepository;
    private final KnowledgeSourceReferenceRepository sourceRepository;
    private final AuditService auditService;

    public KnowledgeQuestionAnswerCurationService(
            KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeSourceReferenceRepository sourceRepository,
            AuditService auditService) {
        this.qaRepository = qaRepository;
        this.sourceRepository = sourceRepository;
        this.auditService = auditService;
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<KnowledgeQaResponse> list(
            UUID organizationId,
            KnowledgeCurationStatus status,
            KnowledgeTopic topic,
            Pageable pageable) {

        Page<KnowledgeQuestionAnswer> page;
        if (status != null && topic != null) {
            page = qaRepository.findByOrganizationIdAndCurationStatusAndTopic(
                    organizationId, status, topic, pageable);
        } else if (status != null) {
            page = qaRepository.findByOrganizationIdAndCurationStatus(organizationId, status, pageable);
        } else if (topic != null) {
            page = qaRepository.findByOrganizationIdAndTopic(organizationId, topic, pageable);
        } else {
            page = qaRepository.findByOrganizationId(organizationId, pageable);
        }
        return page.map(KnowledgeQaResponse::from);
    }

    @Transactional(readOnly = true)
    public KnowledgeQaDetailResponse getDetail(UUID organizationId, UUID id) {
        KnowledgeQuestionAnswer qa = requireOwned(organizationId, id);
        List<KnowledgeSourceReference> sources = sourceRepository.findByQuestionAnswerId(id);
        return KnowledgeQaDetailResponse.from(qa, sources);
    }

    // -------------------------------------------------------------------------
    // Curation updates
    // -------------------------------------------------------------------------

    @Transactional
    public KnowledgeQaDetailResponse updateCuration(
            UUID organizationId, UUID actingUserId, UUID id, KnowledgeQaCurationRequest req) {

        KnowledgeQuestionAnswer qa = requireOwned(organizationId, id);
        qa.updateCuration(
                req.normalizedQuestion(),
                req.shortAnswer(),
                req.technicalAnswer(),
                req.topic(),
                req.subtopic(),
                req.jurisdiction(),
                req.riskLevel() != null ? req.riskLevel() : qa.getRiskLevel(),
                req.requiresHumanValidation() != null
                        ? req.requiresHumanValidation() : qa.isRequiresHumanValidation(),
                req.validFrom(),
                req.validTo(),
                req.notes());

        qaRepository.save(qa);
        auditService.record(organizationId, actingUserId,
                AuditAction.KNOWLEDGE_QA_UPDATED, "KnowledgeQuestionAnswer", id);

        List<KnowledgeSourceReference> sources = sourceRepository.findByQuestionAnswerId(id);
        return KnowledgeQaDetailResponse.from(qa, sources);
    }

    @Transactional
    public void markPendingReview(UUID organizationId, UUID actingUserId, UUID id) {
        KnowledgeQuestionAnswer qa = requireOwned(organizationId, id);
        var previousStatus = qa.getCurationStatus();
        qa.markPendingReview();
        qaRepository.save(qa);
        auditService.record(organizationId, actingUserId,
                AuditAction.KNOWLEDGE_QA_STATUS_CHANGED, "KnowledgeQuestionAnswer", id,
                "previousStatus=%s newStatus=PENDING_REVIEW".formatted(previousStatus));
    }

    @Transactional
    public void validate(UUID organizationId, UUID actingUserId, String reviewerName, UUID id) {
        KnowledgeQuestionAnswer qa = requireOwned(organizationId, id);

        // Enforce source requirement at service layer (entity cannot query repository)
        long sourceCount = sourceRepository.countByQuestionAnswerId(id);
        if (sourceCount == 0) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "At least one source reference is required before validating");
        }

        // For HIGH/CRITICAL risk: require human validation flag
        if ((qa.getRiskLevel() == KnowledgeRiskLevel.HIGH
                || qa.getRiskLevel() == KnowledgeRiskLevel.CRITICAL)
                && !qa.isRequiresHumanValidation()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "HIGH and CRITICAL risk entries must have requiresHumanValidation=true");
        }

        var previousStatus = qa.getCurationStatus();
        qa.validate(reviewerName);
        qaRepository.save(qa);
        auditService.record(organizationId, actingUserId,
                AuditAction.KNOWLEDGE_QA_VALIDATED, "KnowledgeQuestionAnswer", id,
                "reviewer=%s previousStatus=%s newStatus=VALIDATED"
                        .formatted(reviewerName, previousStatus));
    }

    @Transactional
    public void reject(UUID organizationId, UUID actingUserId, String reviewerName, UUID id, String reason) {
        KnowledgeQuestionAnswer qa = requireOwned(organizationId, id);
        var previousStatus = qa.getCurationStatus();
        qa.reject(reviewerName, reason);
        qaRepository.save(qa);
        auditService.record(organizationId, actingUserId,
                AuditAction.KNOWLEDGE_QA_REJECTED, "KnowledgeQuestionAnswer", id,
                "reviewer=%s reason=%s previousStatus=%s newStatus=REJECTED"
                        .formatted(reviewerName, reason, previousStatus));
    }

    @Transactional
    public void markOutdated(UUID organizationId, UUID actingUserId, UUID id) {
        KnowledgeQuestionAnswer qa = requireOwned(organizationId, id);
        var previousStatus = qa.getCurationStatus();
        qa.markOutdated();
        qaRepository.save(qa);
        auditService.record(organizationId, actingUserId,
                AuditAction.KNOWLEDGE_QA_STATUS_CHANGED, "KnowledgeQuestionAnswer", id,
                "previousStatus=%s newStatus=OUTDATED".formatted(previousStatus));
    }

    @Transactional
    public void archive(UUID organizationId, UUID actingUserId, UUID id) {
        KnowledgeQuestionAnswer qa = requireOwned(organizationId, id);
        var previousStatus = qa.getCurationStatus();
        qa.archive();
        qaRepository.save(qa);
        auditService.record(organizationId, actingUserId,
                AuditAction.KNOWLEDGE_QA_ARCHIVED, "KnowledgeQuestionAnswer", id,
                "previousStatus=%s newStatus=ARCHIVED".formatted(previousStatus));
    }

    // -------------------------------------------------------------------------
    // Canonical management
    // -------------------------------------------------------------------------

    @Transactional
    public void setCanonical(UUID organizationId, UUID actingUserId, UUID id, boolean canonical) {
        KnowledgeQuestionAnswer qa = requireOwned(organizationId, id);

        if (canonical && qa.getTopic() != null) {
            List<KnowledgeQuestionAnswer> conflicts =
                    qaRepository.findCanonicalConflicts(organizationId, qa.getTopic(), id);
            if (!conflicts.isEmpty()) {
                throw new BusinessException(ApiErrorCode.CONFLICT,
                        "Another canonical entry already exists for topic %s (id=%s)"
                                .formatted(qa.getTopic(), conflicts.get(0).getId()));
            }
        }

        qa.setCanonical(canonical);
        qaRepository.save(qa);
        auditService.record(organizationId, actingUserId,
                AuditAction.KNOWLEDGE_QA_UPDATED, "KnowledgeQuestionAnswer", id,
                "canonical=%s".formatted(canonical));
    }

    // -------------------------------------------------------------------------
    // Sources
    // -------------------------------------------------------------------------

    @Transactional
    public SourceReferenceResponse addSource(
            UUID organizationId, UUID actingUserId, UUID qaId, SourceReferenceRequest req) {

        KnowledgeQuestionAnswer qa = requireOwned(organizationId, qaId);
        requireValidUrl(req.url());

        KnowledgeSourceReference src = new KnowledgeSourceReference(qa, req.sourceType(), req.title());
        src.update(req.sourceType(), req.title(), req.legalReference(), req.url(),
                req.documentId(), req.fragmentId(), req.validFrom(), req.validTo(), req.notes());
        sourceRepository.save(src);

        auditService.record(organizationId, actingUserId,
                AuditAction.KNOWLEDGE_QA_SOURCE_ADDED, "KnowledgeQuestionAnswer", qaId,
                "sourceType=%s title=%s".formatted(req.sourceType(), req.title()));

        return SourceReferenceResponse.from(src);
    }

    @Transactional(readOnly = true)
    public List<SourceReferenceResponse> listSources(UUID organizationId, UUID qaId) {
        requireOwned(organizationId, qaId);
        return sourceRepository.findByQuestionAnswerId(qaId)
                .stream().map(SourceReferenceResponse::from).toList();
    }

    /**
     * Removes a source reference from a Q&A entry.
     * <p>
     * The module is append-only by design, so removal exists to correct wrong
     * entries — never to strip evidence from knowledge already in use: a
     * VALIDATED or published entry may never be left without any source.
     */
    @Transactional
    public void removeSource(UUID organizationId, UUID actingUserId, UUID qaId, UUID sourceId) {
        KnowledgeQuestionAnswer qa = requireOwned(organizationId, qaId);

        KnowledgeSourceReference src = sourceRepository.findById(sourceId)
                .filter(s -> s.getQuestionAnswer().getId().equals(qaId))
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND,
                        // A source belonging to another entry answers NOT_FOUND, exactly
                        // like a missing id — existence is never revealed.
                        "Source reference not found: " + sourceId));

        boolean inUse = qa.getCurationStatus() == KnowledgeCurationStatus.VALIDATED || qa.isPublished();
        if (inUse && sourceRepository.countByQuestionAnswerId(qaId) <= 1) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "Cannot remove the last source of a validated or published entry — "
                            + "add the correct source first");
        }

        sourceRepository.delete(src);

        auditService.record(organizationId, actingUserId,
                AuditAction.KNOWLEDGE_QA_SOURCE_REMOVED, "KnowledgeQuestionAnswer", qaId,
                "externalKey=%s sourceId=%s sourceType=%s title=%s".formatted(
                        qa.getExternalKey(), sourceId, src.getSourceType(), src.getTitle()));
    }

    /** Source URLs must carry an explicit http/https scheme — no free text, no other schemes. */
    private void requireValidUrl(String url) {
        if (url == null || url.isBlank()) return; // URL is optional
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "Source url must start with http:// or https:// — received: " + trimmed);
        }
    }

    // -------------------------------------------------------------------------
    // Guard
    // -------------------------------------------------------------------------

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
