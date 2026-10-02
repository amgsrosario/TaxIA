package com.knowledgeflow.knowledge.service;

import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.service.AuditService;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.knowledge.entity.KnowledgeQaApplicabilityExclusion;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.governance.CurationChanges;
import com.knowledgeflow.knowledge.rag.KnowledgeQaEmbeddingIndexer;
import com.knowledgeflow.knowledge.repository.KnowledgeQaApplicabilityExclusionRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * Material changes to an already-published entry go through a new, unpublished version
 * (previousVersionId); the published version keeps answering until a human publishes the
 * validated new version replacing it, atomically (ADR-005). At most one version of a lineage
 * is published at any time.
 */
@Service
public class KnowledgeQuestionAnswerPublicationService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQuestionAnswerPublicationService.class);

    private final KnowledgeQuestionAnswerRepository qaRepository;
    private final KnowledgeSourceReferenceRepository sourceRepository;
    private final KnowledgeQaApplicabilityExclusionRepository exclusionRepository;
    private final KnowledgeQaEmbeddingIndexer indexer;
    private final AuditService auditService;
    private final com.knowledgeflow.common.observability.KnowledgeFlowMetrics metrics;

    public KnowledgeQuestionAnswerPublicationService(
            KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeSourceReferenceRepository sourceRepository,
            KnowledgeQaApplicabilityExclusionRepository exclusionRepository,
            KnowledgeQaEmbeddingIndexer indexer,
            AuditService auditService,
            com.knowledgeflow.common.observability.KnowledgeFlowMetrics metrics) {
        this.qaRepository = qaRepository;
        this.sourceRepository = sourceRepository;
        this.exclusionRepository = exclusionRepository;
        this.indexer = indexer;
        this.auditService = auditService;
        this.metrics = metrics;
    }

    // -------------------------------------------------------------------------
    // Publish
    // -------------------------------------------------------------------------

    @Transactional
    public void publish(UUID organizationId, UUID actingUserId, String publisherName, UUID id) {
        // Lock da raiz da linhagem antes da própria linha: serializa publicações concorrentes de
        // versões da mesma linhagem (nunca duas publicadas).
        UUID rootId = lineageRootId(id);
        requireOwnedForUpdate(organizationId, rootId);
        KnowledgeQuestionAnswer qa = requireOwnedForUpdate(organizationId, id);

        if (qa.isPublished()) {
            throw new BusinessException(ApiErrorCode.CONFLICT,
                    "Entry %s is already published".formatted(id));
        }
        // Nunca duas versões publicadas da mesma linhagem: substituir é uma operação própria.
        UUID publishedRelative = publishedInLineage(qa);
        if (publishedRelative != null) {
            throw new BusinessException(ApiErrorCode.CONFLICT,
                    "Another version of this entry is published (%s) — use publish-replacing".formatted(publishedRelative));
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
        // Lock: uma substituição concorrente não pode deixar um embedding órfão numa versão já despublicada.
        KnowledgeQuestionAnswer qa = requireOwnedForUpdate(organizationId, id);

        if (!qa.isPublished()) {
            throw new BusinessException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "Only published entries can be reindexed — use publish for entry %s".formatted(id));
        }
        // Só conteúdo validado da versão publicada (congelada para alterações materiais, ADR-005).
        if (qa.getCurationStatus() != KnowledgeCurationStatus.VALIDATED) {
            throw new BusinessException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "Entry %s is published but not VALIDATED (%s) — reindexing is blocked"
                            .formatted(id, qa.getCurationStatus()));
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
        unpublish(organizationId, actingUserId, id, null);
    }

    /**
     * Unpublishes an entry and records the {@code KNOWLEDGE_QA_UNPUBLISHED} audit event with an
     * optional governance detail (e.g. the taxonomy-based rollback motive — Bloco E, E10-policy-impl).
     * The three-argument overload delegates here with {@code null}, so its behaviour is unchanged.
     * The detail is persisted in the existing {@code audit_events.metadata} TEXT column — no new audit
     * action, no schema change.
     */
    @Transactional
    public void unpublish(UUID organizationId, UUID actingUserId, UUID id, String governanceDetail) {
        KnowledgeQuestionAnswer qa = requireOwned(organizationId, id);

        if (!qa.isPublished()) {
            throw new BusinessException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "Entry %s is not currently published".formatted(id));
        }

        indexer.remove(qa.getId());
        qa.markUnpublished();
        qaRepository.save(qa);

        String detail = (governanceDetail == null || governanceDetail.isBlank()) ? null : governanceDetail;
        auditService.record(organizationId, actingUserId,
                AuditAction.KNOWLEDGE_QA_UNPUBLISHED, "KnowledgeQuestionAnswer", id, detail);

        log.info("Unpublished QA qaId={} org={}", id, organizationId);
    }

    // -------------------------------------------------------------------------
    // New version (when answer changes after publication)
    // -------------------------------------------------------------------------

    /**
     * Cria uma nova versão, não publicada, de uma versão publicada (ADR-005). Copia o conteúdo
     * curado, as fontes (linhas novas) e as exclusões efectivas (pedidos de remoção pendentes
     * ficam efectivos); não copia publicação, validação nem revisão de âmbito. A versão anterior
     * continua publicada, VALIDATED e indexada. Uma só versão em preparação por versão publicada.
     */
    @Transactional
    public KnowledgeQuestionAnswer createNewVersion(UUID organizationId, UUID actingUserId, UUID previousId) {
        KnowledgeQuestionAnswer previous = requireOwnedForUpdate(organizationId, previousId);
        if (!previous.isPublished()) {
            throw new BusinessException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "Entry %s is not published — edit it directly (material changes return it to review)"
                            .formatted(previousId));
        }
        for (KnowledgeQuestionAnswer child : qaRepository.findByPreviousVersionId(previousId)) {
            if (!child.isPublished() && isInPreparation(child)) {
                throw new BusinessException(ApiErrorCode.CONFLICT,
                        "A new version of entry %s is already in preparation (%s)".formatted(previousId, child.getId()));
            }
        }

        KnowledgeQuestionAnswer newVersion = new KnowledgeQuestionAnswer(
                previous.getOrganization(),
                previous.getOriginalQuestion(),
                previous.getOriginalAnswer(),
                previous.getSourceSystem(),
                nextVersionKey(previous));
        newVersion.updateCuration(
                previous.getNormalizedQuestion(),
                previous.getShortAnswer(),
                previous.getTechnicalAnswer(),
                previous.getTopic(),
                previous.getSubtopic(),
                previous.getJurisdiction(),
                previous.getRiskLevel(),
                previous.isRequiresHumanValidation(),
                previous.getValidFrom(),
                previous.getValidTo(),
                previous.getNotes());
        newVersion.setPreviousVersionId(previousId);
        // Começa em revisão: só uma nova validação humana a torna VALIDATED.
        newVersion.markPendingReview();
        qaRepository.save(newVersion);

        List<KnowledgeSourceReference> sources = sourceRepository.findByQuestionAnswerId(previousId);
        for (KnowledgeSourceReference source : sources) {
            KnowledgeSourceReference copy = new KnowledgeSourceReference(newVersion, source.getSourceType(), source.getTitle());
            copy.update(source.getSourceType(), source.getTitle(), source.getLegalReference(), source.getUrl(),
                    source.getDocumentId(), source.getFragmentId(), source.getValidFrom(), source.getValidTo(),
                    source.getNotes());
            sourceRepository.save(copy);
        }

        // As exclusões de aplicabilidade (ADR-004) passam para a nova versão — nunca se alarga o
        // âmbito em silêncio. Pedidos de remoção pendentes não passam: a exclusão fica efectiva.
        // A marca "âmbito revisto" não passa: a nova versão pede nova revisão.
        List<String> inherited = new ArrayList<>();
        for (KnowledgeQaApplicabilityExclusion exclusion : exclusionRepository.findByKnowledgeQaId(previousId)) {
            exclusionRepository.save(new KnowledgeQaApplicabilityExclusion(
                    newVersion, exclusion.getMarker(), exclusion.getNote(), exclusion.getCreatedBy()));
            inherited.add(exclusion.getMarker());
        }

        auditService.record(organizationId, actingUserId,
                AuditAction.KNOWLEDGE_QA_VERSION_CREATED, "KnowledgeQuestionAnswer", newVersion.getId(),
                "previousVersionId=%s sourcesCopied=%d previousStaysPublished=true".formatted(previousId, sources.size()));
        if (!inherited.isEmpty()) {
            auditService.record(organizationId, actingUserId,
                    AuditAction.KNOWLEDGE_QA_APPLICABILITY_UPDATED, "KnowledgeQuestionAnswer", newVersion.getId(),
                    "event=INHERITED fromVersion=%s exclusions=%s".formatted(previousId, inherited));
        }

        log.info("Created new version qaId={} previousId={} (previous stays published)", newVersion.getId(), previousId);
        return newVersion;
    }

    /**
     * Atalho para chamadores existentes: cria a nova versão e aplica-lhe uma nova resposta técnica
     * (a nova versão está em revisão, por isso a edição é livre). Não publica nada.
     */
    @Transactional
    public KnowledgeQuestionAnswer createNewVersion(
            UUID organizationId, UUID actingUserId, String editorName, UUID previousId, String newTechnicalAnswer) {
        KnowledgeQuestionAnswer newVersion = createNewVersion(organizationId, actingUserId, previousId);
        CurationChanges changes = newVersion.updateCuration(newVersion.getNormalizedQuestion(), newVersion.getShortAnswer(), newTechnicalAnswer,
                newVersion.getTopic(), newVersion.getSubtopic(), newVersion.getJurisdiction(),
                newVersion.getRiskLevel(), newVersion.isRequiresHumanValidation(), newVersion.getValidFrom(),
                newVersion.getValidTo(), newVersion.getNotes());
        qaRepository.save(newVersion);
        if (!changes.isEmpty()) {
            auditService.record(organizationId, actingUserId,
                    AuditAction.KNOWLEDGE_QA_UPDATED, "KnowledgeQuestionAnswer", newVersion.getId(),
                    "changedFields=%s editor=%s".formatted(changes.fieldNames(), editorName));
        }
        return newVersion;
    }

    // -------------------------------------------------------------------------
    // Publish replacing (atomic substitution)
    // -------------------------------------------------------------------------

    /**
     * Publica a versão VALIDATED {@code newId} e despublica, na mesma transacção, a versão
     * publicada {@code previousId} de que ela deriva (ADR-005). Locks nas duas linhas por ordem
     * determinística. Se a indexação da nova falhar, tudo é revertido: a anterior continua
     * publicada e indexada, a nova continua por publicar. Acção humana explícita.
     */
    @Transactional
    public void publishReplacing(
            UUID organizationId, UUID actingUserId, String publisherName, UUID newId, UUID previousId) {
        if (publisherName == null || publisherName.isBlank()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR, "publisherName is required");
        }
        if (newId.equals(previousId)) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR, "An entry cannot replace itself");
        }
        // Ordem determinística dos locks: sempre a raiz da linhagem primeiro (mesmo que seja uma das
        // duas linhas), depois as restantes por id — a mesma ordem usada por publish.
        UUID rootId = lineageRootId(previousId);
        requireOwnedForUpdate(organizationId, rootId);
        KnowledgeQuestionAnswer newVersion;
        KnowledgeQuestionAnswer previous;
        if (newId.compareTo(previousId) < 0) {
            newVersion = requireOwnedForUpdate(organizationId, newId);
            previous = requireOwnedForUpdate(organizationId, previousId);
        } else {
            previous = requireOwnedForUpdate(organizationId, previousId);
            newVersion = requireOwnedForUpdate(organizationId, newId);
        }

        if (!previousId.equals(newVersion.getPreviousVersionId())) {
            throw new BusinessException(ApiErrorCode.CONFLICT,
                    "Entry %s is not a new version of %s".formatted(newId, previousId));
        }
        if (!previous.isPublished()) {
            throw new BusinessException(ApiErrorCode.CONFLICT,
                    "Entry %s is not published — publish %s directly".formatted(previousId, newId));
        }
        if (newVersion.isPublished()) {
            throw new BusinessException(ApiErrorCode.CONFLICT, "Entry %s is already published".formatted(newId));
        }
        assertEligible(newVersion);

        String topicLabel = newVersion.getTopic() != null ? newVersion.getTopic().name() : null;
        try {
            indexer.index(newVersion.getId(), newVersion.getOriginalQuestion(), activeAnswer(newVersion), topicLabel);
        } catch (RuntimeException e) {
            metrics.recordPublication("embedding_failure");
            throw e; // rollback: a anterior mantém-se publicada e indexada
        }
        newVersion.markPublished(publisherName);
        indexer.remove(previous.getId());
        previous.markUnpublished();
        qaRepository.save(newVersion);
        qaRepository.save(previous);

        auditService.record(organizationId, actingUserId,
                AuditAction.KNOWLEDGE_QA_PUBLISHED, "KnowledgeQuestionAnswer", newId,
                "publisher=%s replaces=%s".formatted(publisherName, previousId));
        auditService.record(organizationId, actingUserId,
                AuditAction.KNOWLEDGE_QA_UNPUBLISHED, "KnowledgeQuestionAnswer", previousId,
                "replacedBy=%s publisher=%s".formatted(newId, publisherName));
        metrics.recordPublication("success");
        log.info("Published QA qaId={} replacing qaId={} org={}", newId, previousId, organizationId);
    }

    /**
     * Raiz da linhagem (versão sem previous_version_id), percorrida só por ids — nenhuma entidade é
     * carregada antes dos locks, que depois a lêem fresca.
     */
    private UUID lineageRootId(UUID id) {
        UUID rootId = id;
        Set<UUID> seen = new HashSet<>();
        Optional<UUID> previous = qaRepository.findPreviousVersionIdById(id);
        while (previous.isPresent() && seen.add(rootId)) {
            rootId = previous.get();
            previous = qaRepository.findPreviousVersionIdById(rootId);
        }
        return rootId;
    }

    private static boolean isInPreparation(KnowledgeQuestionAnswer qa) {
        return switch (qa.getCurationStatus()) {
            case IMPORTED, PENDING_REVIEW, VALIDATED, NEEDS_UPDATE -> true;
            default -> false;
        };
    }

    /** Próxima chave da família: base (sem _vN) + _v(max+1); determinística sob o lock da versão publicada. */
    private String nextVersionKey(KnowledgeQuestionAnswer previous) {
        String key = previous.getExternalKey();
        if (key == null) {
            return null;
        }
        String base = key.replaceFirst("_v\\d+$", "");
        int max = 1;
        for (String existing : qaRepository.findVersionKeys(
                previous.getOrganization().getId(), previous.getSourceSystem(), base)) {
            Matcher m = Pattern.compile(
                    Pattern.quote(base) + "_v(\\d+)").matcher(existing);
            if (m.matches() && m.group(1).length() <= 6) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        String versionedKey = base + "_v" + (max + 1);
        if (versionedKey.length() > KnowledgeQuestionAnswer.EXTERNAL_KEY_MAX_LENGTH) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "externalKey of entry %s is too long to receive the versioning suffix (max %d)"
                            .formatted(previous.getId(), KnowledgeQuestionAnswer.EXTERNAL_KEY_MAX_LENGTH));
        }
        return versionedKey;
    }

    /** Id de outra versão publicada da mesma linhagem (ascendentes e descendentes), ou null. */
    private UUID publishedInLineage(KnowledgeQuestionAnswer qa) {
        Set<UUID> seen = new HashSet<>();
        seen.add(qa.getId());
        UUID ancestorId = qa.getPreviousVersionId();
        while (ancestorId != null && seen.add(ancestorId)) {
            KnowledgeQuestionAnswer ancestor = qaRepository.findById(ancestorId).orElse(null);
            if (ancestor == null) break;
            if (ancestor.isPublished()) return ancestor.getId();
            ancestorId = ancestor.getPreviousVersionId();
        }
        Deque<UUID> pending = new ArrayDeque<>(List.of(qa.getId()));
        while (!pending.isEmpty()) {
            for (KnowledgeQuestionAnswer child : qaRepository.findByPreviousVersionId(pending.pop())) {
                if (!seen.add(child.getId())) continue;
                if (child.isPublished()) return child.getId();
                pending.push(child.getId());
            }
        }
        return null;
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
        if (qa.getCurationStatus() != KnowledgeCurationStatus.VALIDATED) {
            return "status is " + qa.getCurationStatus();
        }
        if (qa.getTechnicalAnswer() == null || qa.getTechnicalAnswer().isBlank()) {
            return "a technicalAnswer is required for publication (shortAnswer alone is never enough)";
        }
        if (qa.getValidTo() != null && qa.getValidTo().isBefore(LocalDate.now())) {
            return "validTo expired on " + qa.getValidTo();
        }
        if ((qa.getRiskLevel() == KnowledgeRiskLevel.HIGH
                || qa.getRiskLevel() == KnowledgeRiskLevel.CRITICAL)
                && (qa.getReviewedBy() == null || qa.getReviewedBy().isBlank())) {
            return "HIGH/CRITICAL risk requires reviewedBy";
        }
        return "unknown reason";
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
