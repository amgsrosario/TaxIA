package com.knowledgeflow.knowledge.service;

import com.knowledgeflow.ai.grounding.scope.FiscalScope;
import com.knowledgeflow.ai.grounding.scope.FiscalScopeClassifier;
import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.service.AuditService;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.knowledge.dto.ApplicabilityExclusionRequest;
import com.knowledgeflow.knowledge.dto.ApplicabilityExclusionResponse;
import com.knowledgeflow.knowledge.dto.ApplicabilityMarkerOption;
import com.knowledgeflow.knowledge.dto.KnowledgeQaApplicabilityResponse;
import com.knowledgeflow.knowledge.dto.KnowledgeQaApplicabilityResponse.DerivedScope;
import com.knowledgeflow.knowledge.entity.KnowledgeQaApplicabilityExclusion;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.ApplicabilityMarker;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.repository.KnowledgeQaApplicabilityExclusionRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Exclusões de aplicabilidade governadas (M4-SCOPE-V2, ADR-004).
 *
 * <ul>
 *   <li>Adicionar estreita o âmbito: efectivo de imediato, mesmo em Q&amp;A publicada, sem nova
 *       validação, sem reindexação, sem mudar o estado de curadoria.</li>
 *   <li>Remover numa Q&amp;A que não pode ser publicada sem nova validação (não publicada e não
 *       VALIDATED): efectivo de imediato.</li>
 *   <li>Remover numa Q&amp;A publicada — ou VALIDATED, que pode ser (re)publicada sem nova
 *       validação — alarga o âmbito: fica apenas pedido; a exclusão continua efectiva até um humano
 *       aprovar a remoção ({@link #approveRemoval}). Uma exclusão com remoção pendente só sai por
 *       aprovação ou fica por cancelamento. Nunca há remoção efectiva silenciosa.</li>
 * </ul>
 * Cada operação é transaccional, com lock de escrita na Q&amp;A (serializa com a publicação), e
 * auditada com {@code KNOWLEDGE_QA_APPLICABILITY_UPDATED}. Mudanças efectivas de exclusões
 * repõem o âmbito como "não revisto".
 */
@Service
public class KnowledgeQaApplicabilityService {

    static final int MAX_NOTE_LENGTH = 500;
    static final int MAX_ACTOR_LENGTH = 255;
    private static final String ENTITY = "KnowledgeQuestionAnswer";

    private final KnowledgeQuestionAnswerRepository qaRepository;
    private final KnowledgeQaApplicabilityExclusionRepository exclusionRepository;
    private final FiscalScopeClassifier classifier;
    private final AuditService auditService;

    public KnowledgeQaApplicabilityService(
            KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeQaApplicabilityExclusionRepository exclusionRepository,
            FiscalScopeClassifier classifier,
            AuditService auditService) {
        this.qaRepository = qaRepository;
        this.exclusionRepository = exclusionRepository;
        this.classifier = classifier;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public KnowledgeQaApplicabilityResponse get(UUID organizationId, UUID qaId) {
        return response(requireOwned(organizationId, qaId));
    }

    @Transactional
    public KnowledgeQaApplicabilityResponse addExclusion(
            UUID organizationId, UUID actingUserId, String actor, UUID qaId, ApplicabilityExclusionRequest request) {
        KnowledgeQuestionAnswer qa = requireOwnedForUpdate(organizationId, qaId);
        ApplicabilityMarker marker = requireMarker(request == null ? null : request.marker());
        String note = normalizeNote(request.note());
        if (exclusionRepository.findByKnowledgeQaIdAndMarker(qaId, marker.name()).isPresent()) {
            throw new BusinessException(ApiErrorCode.CONFLICT,
                    "Exclusion %s already exists for this entry".formatted(marker.name()));
        }
        try {
            exclusionRepository.saveAndFlush(new KnowledgeQaApplicabilityExclusion(qa, marker.name(), note, actor));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ApiErrorCode.CONFLICT,
                    "Exclusion %s already exists for this entry".formatted(marker.name()));
        }
        clearReview(qa);
        audit(organizationId, actingUserId, qa, "ADDED", marker.name(), actor, note == null ? null : "note=" + quoted(note));
        return response(qa);
    }

    /**
     * Pede ou executa a remoção. Q&amp;A publicada ou VALIDATED: só regista o pedido (a exclusão
     * continua efectiva). Restantes: remove de imediato. Uma remoção já pendente não pode ser
     * contornada por este caminho (409): aprova-se ou cancela-se.
     */
    @Transactional
    public KnowledgeQaApplicabilityResponse removeExclusion(
            UUID organizationId, UUID actingUserId, String actor, UUID qaId, String markerCode) {
        KnowledgeQuestionAnswer qa = requireOwnedForUpdate(organizationId, qaId);
        KnowledgeQaApplicabilityExclusion exclusion = requireExclusion(qaId, markerCode);
        if (exclusion.isRemovalPending()) {
            throw new BusinessException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "Removal of %s is already pending human validation — approve or cancel it"
                            .formatted(exclusion.getMarker()));
        }
        if (removalNeedsValidation(qa)) {
            exclusion.requestRemoval(actor);
            exclusionRepository.save(exclusion);
            audit(organizationId, actingUserId, qa, "REMOVAL_REQUESTED", exclusion.getMarker(), actor, null);
            return response(qa);
        }
        exclusionRepository.delete(exclusion);
        exclusionRepository.flush();
        clearReview(qa);
        audit(organizationId, actingUserId, qa, "REMOVED", exclusion.getMarker(), actor,
                "curationStatus=" + qa.getCurationStatus());
        return response(qa);
    }

    /** Validação humana de uma remoção pendente: só então a exclusão deixa de ser efectiva. */
    @Transactional
    public KnowledgeQaApplicabilityResponse approveRemoval(
            UUID organizationId, UUID actingUserId, String reviewerName, UUID qaId, String markerCode) {
        requireReviewer(reviewerName);
        KnowledgeQuestionAnswer qa = requireOwnedForUpdate(organizationId, qaId);
        KnowledgeQaApplicabilityExclusion exclusion = requirePending(requireExclusion(qaId, markerCode));
        exclusionRepository.delete(exclusion);
        exclusionRepository.flush();
        clearReview(qa);
        audit(organizationId, actingUserId, qa, "REMOVAL_APPROVED", exclusion.getMarker(), reviewerName.trim(),
                "requestedBy=" + exclusion.getRemovalRequestedBy());
        return response(qa);
    }

    /** Desiste de um pedido de remoção pendente; a exclusão mantém-se. */
    @Transactional
    public KnowledgeQaApplicabilityResponse cancelRemoval(
            UUID organizationId, UUID actingUserId, String actor, UUID qaId, String markerCode) {
        KnowledgeQuestionAnswer qa = requireOwnedForUpdate(organizationId, qaId);
        KnowledgeQaApplicabilityExclusion exclusion = requirePending(requireExclusion(qaId, markerCode));
        exclusion.cancelRemovalRequest();
        exclusionRepository.save(exclusion);
        audit(organizationId, actingUserId, qa, "REMOVAL_CANCELLED", exclusion.getMarker(), actor, null);
        return response(qa);
    }

    /** "Âmbito revisto": regista quem e quando, com ou sem exclusões. */
    @Transactional
    public KnowledgeQaApplicabilityResponse markReviewed(
            UUID organizationId, UUID actingUserId, String reviewerName, UUID qaId) {
        requireReviewer(reviewerName);
        KnowledgeQuestionAnswer qa = requireOwnedForUpdate(organizationId, qaId);
        qa.markApplicabilityReviewed(reviewerName.trim());
        qaRepository.save(qa);
        List<String> markers = exclusionRepository.findByKnowledgeQaId(qaId).stream()
                .map(KnowledgeQaApplicabilityExclusion::getMarker).toList();
        audit(organizationId, actingUserId, qa, "REVIEWED", null, reviewerName.trim(), "exclusions=" + markers);
        return response(qa);
    }

    // -------------------------------------------------------------------------

    private KnowledgeQaApplicabilityResponse response(KnowledgeQuestionAnswer qa) {
        String question = qa.getNormalizedQuestion() != null && !qa.getNormalizedQuestion().isBlank()
                ? qa.getNormalizedQuestion() : qa.getOriginalQuestion();
        FiscalScope scope = classifier.classifyCandidate(qa.getTopic(), qa.getSubtopic(), question);
        DerivedScope derived = new DerivedScope(
                scope.domains().stream().map(Enum::name).toList(),
                scope.incomeCategories().stream().map(Enum::name).toList(),
                scope.operations().stream().map(Enum::name).toList());
        return new KnowledgeQaApplicabilityResponse(
                qa.getId(),
                qa.isPublished(),
                removalNeedsValidation(qa),
                qa.getApplicabilityReviewedAt(),
                qa.getApplicabilityReviewedBy(),
                derived,
                exclusionRepository.findByKnowledgeQaId(qa.getId()).stream()
                        .map(ApplicabilityExclusionResponse::from).toList(),
                vocabulary());
    }

    /**
     * Retirar uma exclusão alarga o âmbito. Só é imediato quando a Q&amp;A não pode chegar ao RAG
     * sem nova validação humana (não publicada e não VALIDATED); caso contrário fica pendente.
     */
    private static boolean removalNeedsValidation(KnowledgeQuestionAnswer qa) {
        return qa.isPublished() || qa.getCurationStatus() == KnowledgeCurationStatus.VALIDATED;
    }

    private void clearReview(KnowledgeQuestionAnswer qa) {
        if (qa.getApplicabilityReviewedAt() != null) {
            qa.clearApplicabilityReview();
        }
        // ADR-005: o âmbito faz parte do que é validado — muda a versão da Q&A
        qa.markEvidenceChanged();
        qaRepository.save(qa);
    }

    /** Texto livre entre aspas (com aspas internas escapadas), para não confundir o par chave=valor. */
    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static List<ApplicabilityMarkerOption> vocabulary() {
        return Arrays.stream(ApplicabilityMarker.values())
                .map(m -> new ApplicabilityMarkerOption(m.name(), m.label())).toList();
    }

    private void audit(UUID organizationId, UUID actingUserId, KnowledgeQuestionAnswer qa, String event,
            String marker, String actor, String detail) {
        StringBuilder metadata = new StringBuilder("event=").append(event);
        if (marker != null) metadata.append(" marker=").append(marker);
        metadata.append(" published=").append(qa.isPublished());
        if (actor != null) metadata.append(" actor=").append(actor);
        if (detail != null) metadata.append(" ").append(detail);
        auditService.record(organizationId, actingUserId, AuditAction.KNOWLEDGE_QA_APPLICABILITY_UPDATED,
                ENTITY, qa.getId(), metadata.toString());
    }

    private static ApplicabilityMarker requireMarker(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR, "marker is required");
        }
        return ApplicabilityMarker.fromCode(code.trim()).orElseThrow(() -> new BusinessException(
                ApiErrorCode.VALIDATION_ERROR, "Unknown applicability marker: " + code.trim()));
    }

    private static String normalizeNote(String note) {
        if (note == null || note.isBlank()) return null;
        String trimmed = note.trim();
        if (trimmed.length() > MAX_NOTE_LENGTH) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "note exceeds %d characters".formatted(MAX_NOTE_LENGTH));
        }
        return trimmed;
    }

    private static void requireReviewer(String reviewerName) {
        if (reviewerName == null || reviewerName.isBlank()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR, "reviewerName is required");
        }
        if (reviewerName.trim().length() > MAX_ACTOR_LENGTH) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "reviewerName exceeds %d characters".formatted(MAX_ACTOR_LENGTH));
        }
    }

    private KnowledgeQaApplicabilityExclusion requireExclusion(UUID qaId, String markerCode) {
        return exclusionRepository.findByKnowledgeQaIdAndMarker(qaId, markerCode == null ? "" : markerCode.trim())
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND,
                        "Applicability exclusion not found: " + markerCode));
    }

    private static KnowledgeQaApplicabilityExclusion requirePending(KnowledgeQaApplicabilityExclusion exclusion) {
        if (!exclusion.isRemovalPending()) {
            throw new BusinessException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "No pending removal for exclusion " + exclusion.getMarker());
        }
        return exclusion;
    }

    private KnowledgeQuestionAnswer requireOwned(UUID organizationId, UUID id) {
        return owned(organizationId, id, qaRepository.findById(id));
    }

    private KnowledgeQuestionAnswer requireOwnedForUpdate(UUID organizationId, UUID id) {
        // Lock de escrita sempre que há transacção (o bean de produção é sempre transaccional);
        // uma instância construída fora do Spring não tem transacção nem nada a serializar.
        return owned(organizationId, id, TransactionSynchronizationManager.isActualTransactionActive()
                ? qaRepository.findByIdForUpdate(id) : qaRepository.findById(id));
    }

    private static KnowledgeQuestionAnswer owned(
            UUID organizationId, UUID id, Optional<KnowledgeQuestionAnswer> found) {
        KnowledgeQuestionAnswer qa = found
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND,
                        "KnowledgeQuestionAnswer not found: " + id));
        if (!qa.getOrganization().getId().equals(organizationId)) {
            // Outra organização responde NOT_FOUND, como um id inexistente.
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "KnowledgeQuestionAnswer not found: " + id);
        }
        return qa;
    }
}
