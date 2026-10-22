package com.knowledgeflow.research.service;

import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.research.entity.CriticalMemoryEntry;
import com.knowledgeflow.research.entity.CriticalMemoryHistory;
import com.knowledgeflow.research.entity.KnowledgeProposition;
import com.knowledgeflow.research.entity.KnowledgePropositionRelation;
import com.knowledgeflow.research.entity.ResearchEvidence;
import com.knowledgeflow.research.entity.ResearchInvestigation;
import com.knowledgeflow.research.entity.ResearchStep;
import com.knowledgeflow.research.repository.CriticalMemoryEntryRepository;
import com.knowledgeflow.research.repository.CriticalMemoryHistoryRepository;
import com.knowledgeflow.research.repository.KnowledgePropositionRelationRepository;
import com.knowledgeflow.research.repository.KnowledgePropositionRepository;
import com.knowledgeflow.research.repository.ResearchEvidenceRepository;
import com.knowledgeflow.research.repository.ResearchInvestigationRepository;
import com.knowledgeflow.research.repository.ResearchStepRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rastreabilidade completa de uma investigação: passos, evidência, proposições (com âncoras,
 * condições e excepções), relações e entradas da memória crítica tocadas pela investigação.
 */
@Service
public class InvestigationTraceService {

    public record Trace(
            ResearchInvestigation investigation,
            List<ResearchStep> steps,
            List<ResearchEvidence> evidence,
            List<KnowledgeProposition> propositions,
            List<KnowledgePropositionRelation> relations,
            List<CriticalMemoryEntry> criticalMemory) {}

    private final ResearchInvestigationRepository investigationRepository;
    private final ResearchStepRepository stepRepository;
    private final ResearchEvidenceRepository evidenceRepository;
    private final KnowledgePropositionRepository propositionRepository;
    private final KnowledgePropositionRelationRepository relationRepository;
    private final CriticalMemoryEntryRepository criticalMemoryRepository;
    private final CriticalMemoryHistoryRepository historyRepository;

    public InvestigationTraceService(ResearchInvestigationRepository investigationRepository,
            ResearchStepRepository stepRepository, ResearchEvidenceRepository evidenceRepository,
            KnowledgePropositionRepository propositionRepository,
            KnowledgePropositionRelationRepository relationRepository,
            CriticalMemoryEntryRepository criticalMemoryRepository, CriticalMemoryHistoryRepository historyRepository) {
        this.investigationRepository = investigationRepository;
        this.stepRepository = stepRepository;
        this.evidenceRepository = evidenceRepository;
        this.propositionRepository = propositionRepository;
        this.relationRepository = relationRepository;
        this.criticalMemoryRepository = criticalMemoryRepository;
        this.historyRepository = historyRepository;
    }

    @Transactional(readOnly = true)
    public Trace trace(UUID organizationId, UUID investigationId) {
        ResearchInvestigation investigation = investigationRepository
                .findByIdAndOrganizationId(investigationId, organizationId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND, "Investigação não encontrada"));
        List<KnowledgeProposition> propositions = propositionRepository.findByInvestigationIdOrderByCreatedAtAsc(investigationId);
        Set<UUID> entryIds = new LinkedHashSet<>();
        for (CriticalMemoryHistory h : historyRepository.findByInvestigationIdOrderByOccurredAtAsc(investigationId)) {
            entryIds.add(h.getEntryId());
        }
        return new Trace(
                investigation,
                stepRepository.findByInvestigationIdOrderByStepOrderAsc(investigationId),
                evidenceRepository.findByInvestigationIdOrderByCreatedAtAsc(investigationId),
                propositions,
                propositions.isEmpty() ? List.of()
                        : relationRepository.findByFromPropositionIdIn(propositions.stream().map(KnowledgeProposition::getId).toList()),
                criticalMemoryRepository.findAllById(entryIds));
    }
}
