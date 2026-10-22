package com.knowledgeflow.research.service;

import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.service.AuditService;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.research.entity.CriticalMemoryEntry;
import com.knowledgeflow.research.entity.CriticalMemoryHistory;
import com.knowledgeflow.research.enums.CriticalClassification;
import com.knowledgeflow.research.enums.CriticalHistoryEvent;
import com.knowledgeflow.research.enums.CriticalStatus;
import com.knowledgeflow.research.repository.CriticalMemoryEntryRepository;
import com.knowledgeflow.research.repository.CriticalMemoryHistoryRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Memória crítica (B1, KF-01 / CA-03).
 * <p>
 * Conserva informação errada, contraditória, insuficiente, rejeitada ou superada, com razão,
 * proveniência, data/versão, relação com o que a contradiz, estado e histórico. A mesma afirmação
 * com a mesma classificação é uma só entrada: uma nova ocorrência incrementa {@code occurrences} e
 * fica no histórico (detecção de recorrência). A afirmação e a proveniência nunca são alteradas;
 * a reavaliação muda apenas o estado, com histórico e auditoria.
 * <p>
 * Nenhum método deste serviço fornece conteúdo para fundamentar respostas: a memória crítica só é
 * consultada para orientar a investigação (por exemplo, não repetir uma hipótese já desmentida).
 */
@Service
public class CriticalMemoryService {

    public static final String SYSTEM_ACTOR = "system:research";

    /** Dados de uma nova observação crítica. */
    public record Observation(
            String claim,
            CriticalClassification classification,
            String reason,
            String provenance,
            String referenceKey,
            String sourceUrl,
            String sourceVersion,
            UUID evidenceId,
            UUID propositionId,
            UUID contradictedByEvidenceId,
            UUID contradictedByPropositionId) {}

    private final CriticalMemoryEntryRepository entryRepository;
    private final CriticalMemoryHistoryRepository historyRepository;
    private final AuditService auditService;
    /** Datas civis em Europe/Lisbon (data de consulta das fontes e vigência). */
    private final Clock clock = Clock.system(ZoneId.of("Europe/Lisbon"));

    public CriticalMemoryService(CriticalMemoryEntryRepository entryRepository,
            CriticalMemoryHistoryRepository historyRepository, AuditService auditService) {
        this.entryRepository = entryRepository;
        this.historyRepository = historyRepository;
        this.auditService = auditService;
    }

    @Transactional
    public CriticalMemoryEntry record(UUID organizationId, UUID investigationId, UUID userId, Observation o) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        String sha = sha256(normalizeClaim(o.claim()));
        Optional<CriticalMemoryEntry> existing = entryRepository
                .findByOrganizationIdAndClassificationAndClaimSha256(organizationId, o.classification(), sha);
        if (existing.isPresent()) {
            CriticalMemoryEntry entry = existing.get();
            entry.recordRecurrence(now);
            entryRepository.save(entry);
            historyRepository.save(new CriticalMemoryHistory(entry.getId(), CriticalHistoryEvent.RECURRED,
                    entry.getClassification(), entry.getClassification(), entry.getStatus(), entry.getStatus(),
                    o.reason(), investigationId, SYSTEM_ACTOR, now));
            auditService.record(organizationId, userId, AuditAction.CRITICAL_MEMORY_RECURRED, "CRITICAL_MEMORY",
                    entry.getId(), "occurrences=%d;investigation=%s".formatted(entry.getOccurrences(), investigationId));
            return entry;
        }
        CriticalMemoryEntry entry = entryRepository.save(new CriticalMemoryEntry(organizationId, investigationId,
                o.evidenceId(), o.propositionId(), o.referenceKey(), o.claim(), sha, o.classification(), o.reason(),
                o.provenance(), o.sourceUrl(), o.sourceVersion(), o.contradictedByEvidenceId(),
                o.contradictedByPropositionId(), now));
        historyRepository.save(new CriticalMemoryHistory(entry.getId(), CriticalHistoryEvent.CREATED, null,
                entry.getClassification(), null, entry.getStatus(), o.reason(), investigationId, SYSTEM_ACTOR, now));
        auditService.record(organizationId, userId, AuditAction.CRITICAL_MEMORY_RECORDED, "CRITICAL_MEMORY",
                entry.getId(), "classification=%s;reference=%s;investigation=%s"
                        .formatted(entry.getClassification(), entry.getReferenceKey(), investigationId));
        return entry;
    }

    /** Recuperação para investigação futura e comparação, por referência legal. */
    @Transactional(readOnly = true)
    public List<CriticalMemoryEntry> findByReferenceKeys(UUID organizationId, Collection<String> referenceKeys) {
        if (referenceKeys.isEmpty()) return List.of();
        return entryRepository.findByOrganizationIdAndReferenceKeyInOrderByCreatedAtAsc(organizationId, referenceKeys);
    }

    @Transactional(readOnly = true)
    public List<CriticalMemoryHistory> history(UUID organizationId, UUID entryId) {
        CriticalMemoryEntry entry = entryRepository.findByIdAndOrganizationId(entryId, organizationId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND, "Entrada da memória crítica não encontrada"));
        return historyRepository.findByEntryIdOrderByOccurredAtAsc(entry.getId());
    }

    /**
     * Reavaliação humana do estado (ACTIVE → UNDER_REEVALUATION → RESOLVED, ou regresso a ACTIVE).
     * Não altera a afirmação, a classificação nem a proveniência; em nenhum estado a entrada passa
     * a fundamento positivo.
     */
    @Transactional
    public CriticalMemoryEntry changeStatus(UUID organizationId, UUID userId, String actor, UUID entryId,
            CriticalStatus newStatus, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR, "A reavaliação exige uma razão");
        }
        CriticalMemoryEntry entry = entryRepository.findByIdAndOrganizationId(entryId, organizationId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND, "Entrada da memória crítica não encontrada"));
        CriticalStatus previous = entry.getStatus();
        if (previous == newStatus) {
            throw new BusinessException(ApiErrorCode.INVALID_STATE_TRANSITION, "A entrada já está em " + newStatus);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        entry.changeStatus(newStatus, now);
        entryRepository.save(entry);
        historyRepository.save(new CriticalMemoryHistory(entry.getId(), CriticalHistoryEvent.STATUS_CHANGED,
                entry.getClassification(), entry.getClassification(), previous, newStatus, reason.strip(), null,
                actor, now));
        auditService.record(organizationId, userId, AuditAction.CRITICAL_MEMORY_STATUS_CHANGED, "CRITICAL_MEMORY",
                entry.getId(), "from=%s;to=%s".formatted(previous, newStatus));
        return entry;
    }

    static String normalizeClaim(String claim) {
        return AnchorVerifier.normalize(claim == null ? "" : claim);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
