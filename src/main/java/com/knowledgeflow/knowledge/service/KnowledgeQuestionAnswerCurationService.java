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
import com.knowledgeflow.knowledge.governance.CurationChanges;
import com.knowledgeflow.knowledge.repository.KnowledgeQaApplicabilityExclusionRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class KnowledgeQuestionAnswerCurationService {

    private final KnowledgeQuestionAnswerRepository qaRepository;
    private final KnowledgeSourceReferenceRepository sourceRepository;
    private final KnowledgeQaApplicabilityExclusionRepository exclusionRepository;
    private final AuditService auditService;

    public KnowledgeQuestionAnswerCurationService(
            KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeSourceReferenceRepository sourceRepository,
            KnowledgeQaApplicabilityExclusionRepository exclusionRepository,
            AuditService auditService) {
        this.qaRepository = qaRepository;
        this.sourceRepository = sourceRepository;
        this.exclusionRepository = exclusionRepository;
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
        return detail(qa, sources);
    }

    // -------------------------------------------------------------------------
    // Curation updates
    // -------------------------------------------------------------------------

    @Transactional
    public KnowledgeQaDetailResponse updateCuration(
            UUID organizationId, UUID actingUserId, UUID id, KnowledgeQaCurationRequest req) {

        KnowledgeQuestionAnswer qa = requireOwnedForUpdate(organizationId, id);
        requireExpectedVersion(qa, req.expectedVersion());
        KnowledgeCurationStatus previousStatus = qa.getCurationStatus();
        CurationChanges changes = qa.updateCuration(
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

        qaRepository.saveAndFlush(qa);
        if (!changes.isEmpty()) {
            // Nomes dos campos e natureza da alteração — nunca o conteúdo (ADR-005).
            auditService.record(organizationId, actingUserId,
                    AuditAction.KNOWLEDGE_QA_UPDATED, "KnowledgeQuestionAnswer", id,
                    "changedFields=%s revalidationFields=%s published=%s".formatted(
                            changes.fieldNames(), changes.revalidationFields(), qa.isPublished()));
        }
        recordReturnToReview(organizationId, actingUserId, qa, previousStatus, "material-curation-change");

        List<KnowledgeSourceReference> sources = sourceRepository.findByQuestionAnswerId(id);
        return detail(qa, sources);
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
        validate(organizationId, actingUserId, reviewerName, id, null);
    }

    /**
     * Valida exactamente a versão que o revisor leu (ADR-005): {@code expectedVersion} diferente da
     * actual → 409. Conteúdo, fontes e exclusões mudam a versão; lock contra edições concorrentes.
     */
    @Transactional
    public void validate(UUID organizationId, UUID actingUserId, String reviewerName, UUID id, Integer expectedVersion) {
        KnowledgeQuestionAnswer qa = requireOwnedForUpdate(organizationId, id);
        requireExpectedVersion(qa, expectedVersion);

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

        KnowledgeQuestionAnswer qa = requireOwnedForUpdate(organizationId, qaId);
        String url = normalizeAndValidateUrl(req.url());
        KnowledgeCurationStatus previousStatus = qa.getCurationStatus();
        // Fonte nova alarga o suporte declarado: recusada se publicada; VALIDATED volta a revisão.
        qa.registerSourceAddition();
        qa.markEvidenceChanged();
        qaRepository.save(qa);

        KnowledgeSourceReference src = new KnowledgeSourceReference(qa, req.sourceType(), req.title());
        src.update(req.sourceType(), req.title(), req.legalReference(), url,
                req.documentId(), req.fragmentId(), req.validFrom(), req.validTo(), req.notes());
        sourceRepository.save(src);

        auditService.record(organizationId, actingUserId,
                AuditAction.KNOWLEDGE_QA_SOURCE_ADDED, "KnowledgeQuestionAnswer", qaId,
                "sourceType=%s title=%s".formatted(req.sourceType(), req.title()));
        recordReturnToReview(organizationId, actingUserId, qa, previousStatus, "source-added");

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
        KnowledgeQuestionAnswer qa = requireOwnedForUpdate(organizationId, qaId);

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
        qa.markEvidenceChanged();
        qaRepository.save(qa);

        auditService.record(organizationId, actingUserId,
                AuditAction.KNOWLEDGE_QA_SOURCE_REMOVED, "KnowledgeQuestionAnswer", qaId,
                "externalKey=%s sourceId=%s sourceType=%s title=%s".formatted(
                        qa.getExternalKey(), sourceId, src.getSourceType(), src.getTitle()));
    }

    private static final int MAX_URL_IN_ERROR = 200;

    /**
     * Source URLs are optional. Null or blank becomes null; anything else is trimmed at the edges
     * (and otherwise left untouched) and must be an absolute http/https URI with a host, scheme
     * compared case-insensitively. Parsing is purely syntactic ({@link URI}): no DNS, no network.
     *
     * @return the value to persist
     */
    private String normalizeAndValidateUrl(String url) {
        if (url == null || url.isBlank()) return null; // URL is optional
        String trimmed = url.trim();
        if (!isAbsoluteHttpUrlWithHost(trimmed)) {
            String shown = trimmed.length() > MAX_URL_IN_ERROR
                    ? trimmed.substring(0, MAX_URL_IN_ERROR) + "…"
                    : trimmed;
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "Source url must be an absolute http:// or https:// URL with a host — received: " + shown);
        }
        return trimmed;
    }

    private static boolean isAbsoluteHttpUrlWithHost(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        return uri.isAbsolute()
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && host != null && !host.isBlank();
    }

    // -------------------------------------------------------------------------
    // Guard
    // -------------------------------------------------------------------------

    private KnowledgeQaDetailResponse detail(KnowledgeQuestionAnswer qa, List<KnowledgeSourceReference> sources) {
        UUID draftId = qaRepository.findByPreviousVersionId(qa.getId()).stream()
                .filter(v -> !v.isPublished())
                .filter(v -> v.getCurationStatus() == KnowledgeCurationStatus.IMPORTED
                        || v.getCurationStatus() == KnowledgeCurationStatus.PENDING_REVIEW
                        || v.getCurationStatus() == KnowledgeCurationStatus.VALIDATED
                        || v.getCurationStatus() == KnowledgeCurationStatus.NEEDS_UPDATE)
                .map(KnowledgeQuestionAnswer::getId).findFirst().orElse(null);
        boolean previousPublished = qa.getPreviousVersionId() != null
                && qaRepository.findById(qa.getPreviousVersionId()).map(KnowledgeQuestionAnswer::isPublished).orElse(false);
        return KnowledgeQaDetailResponse.from(qa, sources, exclusionRepository.findByKnowledgeQaId(qa.getId()),
                draftId, previousPublished);
    }

    /** Versão esperada pelo editor (optimistic lock, ADR-005): diferente da actual → 409. */
    private static void requireExpectedVersion(KnowledgeQuestionAnswer qa, Integer expectedVersion) {
        if (expectedVersion != null && expectedVersion != qa.getVersion()) {
            throw new BusinessException(ApiErrorCode.CONFLICT,
                    "Entry was changed by someone else (expected version %d, current %d) — reload and retry"
                            .formatted(expectedVersion, qa.getVersion()));
        }
    }

    private void recordReturnToReview(UUID organizationId, UUID actingUserId, KnowledgeQuestionAnswer qa,
            KnowledgeCurationStatus previousStatus, String reason) {
        if (previousStatus == KnowledgeCurationStatus.VALIDATED
                && qa.getCurationStatus() == KnowledgeCurationStatus.PENDING_REVIEW) {
            auditService.record(organizationId, actingUserId,
                    AuditAction.KNOWLEDGE_QA_STATUS_CHANGED, "KnowledgeQuestionAnswer", qa.getId(),
                    "previousStatus=VALIDATED newStatus=PENDING_REVIEW reason=%s validationCleared=true"
                            .formatted(reason));
        }
    }

    private KnowledgeQuestionAnswer requireOwnedForUpdate(UUID organizationId, UUID id) {
        // Lock de escrita sempre que há transacção (o bean de produção é sempre transaccional);
        // uma instância construída fora do Spring não tem transacção nem nada a serializar.
        return owned(organizationId, id, TransactionSynchronizationManager.isActualTransactionActive()
                ? qaRepository.findByIdForUpdate(id) : qaRepository.findById(id));
    }

    private KnowledgeQuestionAnswer requireOwned(UUID organizationId, UUID id) {
        return owned(organizationId, id, qaRepository.findById(id));
    }

    private static KnowledgeQuestionAnswer owned(
            UUID organizationId, UUID id, Optional<KnowledgeQuestionAnswer> found) {
        KnowledgeQuestionAnswer qa = found
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
