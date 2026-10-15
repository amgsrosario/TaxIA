package com.knowledgeflow.knowledge.service;

import com.knowledgeflow.ai.grounding.scope.FiscalScope;
import com.knowledgeflow.ai.grounding.scope.FiscalScopeClassifier;
import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.service.AuditService;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.knowledge.dto.ApplicabilityRuleRequest;
import com.knowledgeflow.knowledge.dto.ApplicabilityRuleResponse;
import com.knowledgeflow.knowledge.dto.ApplicabilityMarkerOption;
import com.knowledgeflow.knowledge.dto.KnowledgeQaApplicabilityResponse;
import com.knowledgeflow.knowledge.dto.KnowledgeQaApplicabilityResponse.DerivedScope;
import com.knowledgeflow.knowledge.entity.KnowledgeQaApplicabilityRule;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.ApplicabilityMarker;
import com.knowledgeflow.knowledge.enums.ApplicabilityRuleKind;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.repository.KnowledgeQaApplicabilityRuleRepository;
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
 * Regras de aplicabilidade governadas (M4-SCOPE-V2, ADR-004): exclusões e, desde o ADR-009, contextos
 * obrigatórios. Ambos estreitam o âmbito, por isso seguem a mesma governação.
 *
 * <ul>
 *   <li>Adicionar estreita o âmbito: efectivo de imediato, mesmo em Q&amp;A publicada, sem nova
 *       validação, sem reindexação, sem mudar o estado de curadoria.</li>
 *   <li>Remover numa Q&amp;A que não pode ser publicada sem nova validação (não publicada e não
 *       VALIDATED): efectivo de imediato.</li>
 *   <li>Remover numa Q&amp;A publicada — ou VALIDATED, que pode ser (re)publicada sem nova
 *       validação — alarga o âmbito: fica apenas pedido; a regra continua efectiva até um humano
 *       aprovar a remoção ({@link #approveRemoval}). Uma regra com remoção pendente só sai por
 *       aprovação ou fica por cancelamento. Nunca há remoção efectiva silenciosa.</li>
 * </ul>
 * Cada operação é transaccional, com lock de escrita na Q&amp;A (serializa com a publicação), e
 * auditada com {@code KNOWLEDGE_QA_APPLICABILITY_UPDATED}. Mudanças efectivas de regras
 * repõem o âmbito como "não revisto".
 */
@Service
public class KnowledgeQaApplicabilityService {

    static final int MAX_NOTE_LENGTH = 500;
    static final int MAX_ACTOR_LENGTH = 255;
    private static final String ENTITY = "KnowledgeQuestionAnswer";

    private final KnowledgeQuestionAnswerRepository qaRepository;
    private final KnowledgeQaApplicabilityRuleRepository ruleRepository;
    private final FiscalScopeClassifier classifier;
    private final AuditService auditService;

    public KnowledgeQaApplicabilityService(
            KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeQaApplicabilityRuleRepository ruleRepository,
            FiscalScopeClassifier classifier,
            AuditService auditService) {
        this.qaRepository = qaRepository;
        this.ruleRepository = ruleRepository;
        this.classifier = classifier;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public KnowledgeQaApplicabilityResponse get(UUID organizationId, UUID qaId) {
        return response(requireOwned(organizationId, qaId));
    }

    @Transactional
    public KnowledgeQaApplicabilityResponse addRule(
            UUID organizationId, UUID actingUserId, String actor, UUID qaId, ApplicabilityRuleRequest request) {
        KnowledgeQuestionAnswer qa = requireOwnedForUpdate(organizationId, qaId);
        ApplicabilityMarker marker = requireMarker(request == null ? null : request.marker());
        ApplicabilityRuleKind kind = requireKind(request.kind());
        if (kind == ApplicabilityRuleKind.REQUIRES && !marker.requirable()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "Marker %s cannot be used as required context".formatted(marker.name()));
        }
        String note = normalizeNote(request.note());
        if (ruleRepository.findByKnowledgeQaIdAndMarker(qaId, marker.name()).isPresent()) {
            throw new BusinessException(ApiErrorCode.CONFLICT,
                    "Applicability rule %s already exists for this entry".formatted(marker.name()));
        }
        try {
            ruleRepository.saveAndFlush(
                    new KnowledgeQaApplicabilityRule(qa, marker.name(), kind, note, actor));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ApiErrorCode.CONFLICT,
                    "Applicability rule %s already exists for this entry".formatted(marker.name()));
        }
        clearReview(qa);
        audit(organizationId, actingUserId, qa, "ADDED", marker.name(), actor,
                kindDetail(kind.name(), note == null ? null : "note=" + quoted(note)));
        return response(qa);
    }

    /**
     * Pede ou executa a remoção. Q&amp;A publicada ou VALIDATED: só regista o pedido (a regra
     * continua efectiva). Restantes: remove de imediato. Uma remoção já pendente não pode ser
     * contornada por este caminho (409): aprova-se ou cancela-se.
     */
    @Transactional
    public KnowledgeQaApplicabilityResponse removeRule(
            UUID organizationId, UUID actingUserId, String actor, UUID qaId, String markerCode) {
        KnowledgeQuestionAnswer qa = requireOwnedForUpdate(organizationId, qaId);
        KnowledgeQaApplicabilityRule rule = requireRule(qaId, markerCode);
        if (rule.isRemovalPending()) {
            throw new BusinessException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "Removal of %s is already pending human validation — approve or cancel it"
                            .formatted(rule.getMarker()));
        }
        if (removalNeedsValidation(qa)) {
            rule.requestRemoval(actor);
            ruleRepository.save(rule);
            audit(organizationId, actingUserId, qa, "REMOVAL_REQUESTED", rule.getMarker(), actor,
                    kindDetail(rule.getKind(), null));
            return response(qa);
        }
        ruleRepository.delete(rule);
        ruleRepository.flush();
        clearReview(qa);
        audit(organizationId, actingUserId, qa, "REMOVED", rule.getMarker(), actor,
                kindDetail(rule.getKind(), "curationStatus=" + qa.getCurationStatus()));
        return response(qa);
    }

    /** Validação humana de uma remoção pendente: só então a regra deixa de ser efectiva. */
    @Transactional
    public KnowledgeQaApplicabilityResponse approveRemoval(
            UUID organizationId, UUID actingUserId, String reviewerName, UUID qaId, String markerCode) {
        requireReviewer(reviewerName);
        KnowledgeQuestionAnswer qa = requireOwnedForUpdate(organizationId, qaId);
        KnowledgeQaApplicabilityRule rule = requirePending(requireRule(qaId, markerCode));
        ruleRepository.delete(rule);
        ruleRepository.flush();
        clearReview(qa);
        audit(organizationId, actingUserId, qa, "REMOVAL_APPROVED", rule.getMarker(), reviewerName.trim(),
                kindDetail(rule.getKind(), "requestedBy=" + rule.getRemovalRequestedBy()));
        return response(qa);
    }

    /** Desiste de um pedido de remoção pendente; a regra mantém-se. */
    @Transactional
    public KnowledgeQaApplicabilityResponse cancelRemoval(
            UUID organizationId, UUID actingUserId, String actor, UUID qaId, String markerCode) {
        KnowledgeQuestionAnswer qa = requireOwnedForUpdate(organizationId, qaId);
        KnowledgeQaApplicabilityRule rule = requirePending(requireRule(qaId, markerCode));
        rule.cancelRemovalRequest();
        ruleRepository.save(rule);
        audit(organizationId, actingUserId, qa, "REMOVAL_CANCELLED", rule.getMarker(), actor,
                kindDetail(rule.getKind(), null));
        return response(qa);
    }

    /** "Âmbito revisto": regista quem e quando, com ou sem regras. */
    @Transactional
    public KnowledgeQaApplicabilityResponse markReviewed(
            UUID organizationId, UUID actingUserId, String reviewerName, UUID qaId) {
        requireReviewer(reviewerName);
        KnowledgeQuestionAnswer qa = requireOwnedForUpdate(organizationId, qaId);
        qa.markApplicabilityReviewed(reviewerName.trim());
        qaRepository.save(qa);
        List<KnowledgeQaApplicabilityRule> rules = ruleRepository.findByKnowledgeQaId(qaId);
        List<String> markers = rules.stream().filter(r -> !isRequired(r.getKind()))
                .map(KnowledgeQaApplicabilityRule::getMarker).toList();
        List<String> required = rules.stream().filter(r -> isRequired(r.getKind()))
                .map(KnowledgeQaApplicabilityRule::getMarker).toList();
        audit(organizationId, actingUserId, qa, "REVIEWED", null, reviewerName.trim(),
                "exclusions=" + markers + (required.isEmpty() ? "" : " requiredContext=" + required));
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
                ruleRepository.findByKnowledgeQaId(qa.getId()).stream()
                        .map(ApplicabilityRuleResponse::from).toList(),
                vocabulary());
    }

    /**
     * Retirar uma regra (exclusão ou contexto obrigatório) alarga o âmbito. Só é imediato quando a Q&amp;A não pode chegar ao RAG
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
                .map(m -> new ApplicabilityMarkerOption(m.name(), m.label(), m.requirable())).toList();
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

    /** Tipo pedido; omisso = exclusão (compatível com os clientes anteriores ao ADR-009). */
    private static ApplicabilityRuleKind requireKind(String code) {
        if (code == null || code.isBlank()) {
            return ApplicabilityRuleKind.EXCLUDES;
        }
        return ApplicabilityRuleKind.fromCode(code.trim()).orElseThrow(() -> new BusinessException(
                ApiErrorCode.VALIDATION_ERROR, "Unknown applicability rule kind: " + code.trim()));
    }

    private static boolean isRequired(String kind) {
        return ApplicabilityRuleKind.REQUIRES.name().equals(kind);
    }

    /** Metadata de auditoria: o tipo só aparece para contexto obrigatório (as exclusões mantêm o formato). */
    private static String kindDetail(String kind, String detail) {
        if (!isRequired(kind)) {
            return detail;
        }
        return detail == null ? "kind=" + kind : "kind=" + kind + " " + detail;
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

    private KnowledgeQaApplicabilityRule requireRule(UUID qaId, String markerCode) {
        return ruleRepository.findByKnowledgeQaIdAndMarker(qaId, markerCode == null ? "" : markerCode.trim())
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND,
                        "Applicability rule not found: " + markerCode));
    }

    private static KnowledgeQaApplicabilityRule requirePending(KnowledgeQaApplicabilityRule rule) {
        if (!rule.isRemovalPending()) {
            throw new BusinessException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "No pending removal for applicability rule " + rule.getMarker());
        }
        return rule;
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
