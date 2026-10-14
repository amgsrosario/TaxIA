package com.knowledgeflow.knowledge.service;

import com.knowledgeflow.ai.documented.SourceFreshnessPolicy;
import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.service.AuditService;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.knowledge.dto.SourceReferenceResponse;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verificação humana estruturada da actualidade das fontes (C8, ADR-008).
 *
 * <ul>
 *   <li>Só operações governadas: registar/revogar verificação e marcar/desmarcar matéria estável. O
 *       estado de actualidade nunca é escolhido — é calculado pela {@link SourceFreshnessPolicy}.</li>
 *   <li>Data e responsável vêm do servidor: relógio injectado e utilizador autenticado (referência a
 *       {@code users.id}). A autorização é a da curadoria de Q&amp;A (controller).</li>
 *   <li>Metadados de governação, não conteúdo material: permitidos também em Q&amp;A publicadas, sem
 *       alterar o texto servido nem o estado de curadoria (ADR-005); serializados com a Q&amp;A e
 *       auditados.</li>
 *   <li>Só fontes elegíveis (legislação, FAQ/orientação oficial) podem ser verificadas.</li>
 * </ul>
 */
@Service
public class SourceFreshnessCurationService {

    private static final Set<KnowledgeSourceType> ELIGIBLE = Set.of(
            KnowledgeSourceType.LEGISLATION, KnowledgeSourceType.OFFICIAL_FAQ,
            KnowledgeSourceType.ADMINISTRATIVE_GUIDANCE);

    private final KnowledgeQuestionAnswerRepository qaRepository;
    private final KnowledgeSourceReferenceRepository sourceRepository;
    private final AuditService auditService;
    private final SourceFreshnessPolicy policy;
    private final Clock clock;

    @Autowired
    public SourceFreshnessCurationService(KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeSourceReferenceRepository sourceRepository, AuditService auditService,
            SourceFreshnessPolicy policy) {
        this(qaRepository, sourceRepository, auditService, policy, Clock.systemUTC());
    }

    /** Test seam: relógio fixo. */
    SourceFreshnessCurationService(KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeSourceReferenceRepository sourceRepository, AuditService auditService,
            SourceFreshnessPolicy policy, Clock clock) {
        this.qaRepository = qaRepository;
        this.sourceRepository = sourceRepository;
        this.auditService = auditService;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional
    public SourceReferenceResponse verify(UUID organizationId, UUID actingUserId, UUID qaId, UUID sourceId,
            boolean consolidatedVersionConfirmed) {
        KnowledgeQuestionAnswer qa = lockOwned(organizationId, qaId);
        KnowledgeSourceReference src = sourceOf(qaId, sourceId);
        requireActor(actingUserId);
        if (!ELIGIBLE.contains(src.getSourceType())) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "Only official legislation or official FAQ/guidance sources can be verified (type "
                            + src.getSourceType() + ")");
        }
        if (consolidatedVersionConfirmed && src.getSourceType() != KnowledgeSourceType.LEGISLATION) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "consolidatedVersionConfirmed applies only to legislation sources");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        src.recordVerification(actingUserId, now, consolidatedVersionConfirmed);
        sourceRepository.save(src);
        touch(qa);
        audit(organizationId, actingUserId, AuditAction.KNOWLEDGE_QA_SOURCE_VERIFIED, qa, src,
                "verifiedAt=%s consolidatedVersionConfirmed=%s".formatted(now, consolidatedVersionConfirmed));
        return SourceReferenceResponse.from(src, policy);
    }

    @Transactional
    public SourceReferenceResponse revokeVerification(UUID organizationId, UUID actingUserId, UUID qaId,
            UUID sourceId) {
        KnowledgeQuestionAnswer qa = lockOwned(organizationId, qaId);
        KnowledgeSourceReference src = sourceOf(qaId, sourceId);
        requireActor(actingUserId);
        String previous = "previousVerifiedAt=%s previousVerifiedBy=%s stableMatterCleared=%s".formatted(
                src.getVerifiedAt(), src.getVerifiedBy(), src.isStableMatter());
        src.revokeVerification();
        sourceRepository.save(src);
        touch(qa);
        audit(organizationId, actingUserId, AuditAction.KNOWLEDGE_QA_SOURCE_VERIFICATION_REVOKED, qa, src, previous);
        return SourceReferenceResponse.from(src, policy);
    }

    @Transactional
    public SourceReferenceResponse setStableMatter(UUID organizationId, UUID actingUserId, UUID qaId, UUID sourceId,
            boolean stableMatter, String reason) {
        KnowledgeQuestionAnswer qa = lockOwned(organizationId, qaId);
        KnowledgeSourceReference src = sourceOf(qaId, sourceId);
        requireActor(actingUserId);
        if (stableMatter) {
            if (!ELIGIBLE.contains(src.getSourceType())) {
                throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                        "Stable matter applies only to official legislation or official FAQ/guidance sources");
            }
            if (src.getVerifiedAt() == null) {
                throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                        "Stable matter requires a prior structured verification of the source");
            }
            if (reason == null || reason.isBlank()) {
                throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                        "A justification is required to mark stable matter");
            }
            src.markStableMatter(actingUserId, OffsetDateTime.now(clock), reason);
        } else {
            src.clearStableMatter();
        }
        sourceRepository.save(src);
        touch(qa);
        audit(organizationId, actingUserId, AuditAction.KNOWLEDGE_QA_SOURCE_STABLE_MATTER_UPDATED, qa, src,
                "stableMatter=%s reason=%s".formatted(stableMatter, stableMatter ? abbreviate(reason.strip()) : "-"));
        return SourceReferenceResponse.from(src, policy);
    }

    // --- helpers ---

    private KnowledgeQuestionAnswer lockOwned(UUID organizationId, UUID qaId) {
        KnowledgeQuestionAnswer qa = qaRepository.findByIdForUpdate(qaId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND, "KnowledgeQuestionAnswer not found: " + qaId));
        if (!qa.getOrganization().getId().equals(organizationId)) {
            throw new BusinessException(ApiErrorCode.NOT_FOUND, "KnowledgeQuestionAnswer not found: " + qaId);
        }
        return qa;
    }

    private KnowledgeSourceReference sourceOf(UUID qaId, UUID sourceId) {
        return sourceRepository.findById(sourceId)
                .filter(s -> s.getQuestionAnswer().getId().equals(qaId))
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND, "Source reference not found: " + sourceId));
    }

    private static void requireActor(UUID actingUserId) {
        if (actingUserId == null) {
            throw new BusinessException(ApiErrorCode.FORBIDDEN, "An authenticated curator is required");
        }
    }

    private void touch(KnowledgeQuestionAnswer qa) {
        qa.markEvidenceChanged();
        qaRepository.save(qa);
    }

    private void audit(UUID organizationId, UUID actingUserId, AuditAction action, KnowledgeQuestionAnswer qa,
            KnowledgeSourceReference src, String details) {
        auditService.record(organizationId, actingUserId, action, "KnowledgeQuestionAnswer", qa.getId(),
                "externalKey=%s sourceId=%s sourceType=%s published=%s %s".formatted(
                        qa.getExternalKey(), src.getId(), src.getSourceType(), qa.isPublished(), details));
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
