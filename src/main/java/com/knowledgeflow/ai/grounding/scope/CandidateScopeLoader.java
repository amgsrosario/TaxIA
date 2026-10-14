package com.knowledgeflow.ai.grounding.scope;

import com.knowledgeflow.knowledge.enums.ApplicabilityMarker;
import com.knowledgeflow.knowledge.enums.ApplicabilityRuleKind;
import com.knowledgeflow.knowledge.repository.KnowledgeQaApplicabilityExclusionRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeQaExclusionMarkerRow;
import com.knowledgeflow.knowledge.repository.KnowledgeQaScopeRow;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carrega os dados das Q&amp;A candidatas necessários ao gate: os campos que derivam o âmbito
 * (tema, subtema, pergunta — uma query) e as exclusões de aplicabilidade efectivas (uma segunda
 * query IN). Só leitura; duas queries por pedido, independentemente do número de candidatos.
 */
@Component
public class CandidateScopeLoader {

    private static final Logger log = LoggerFactory.getLogger(CandidateScopeLoader.class);

    private final KnowledgeQuestionAnswerRepository repository;
    private final KnowledgeQaApplicabilityExclusionRepository exclusionRepository;
    private final FiscalScopeClassifier classifier;

    public CandidateScopeLoader(
            KnowledgeQuestionAnswerRepository repository,
            KnowledgeQaApplicabilityExclusionRepository exclusionRepository,
            FiscalScopeClassifier classifier) {
        this.repository = repository;
        this.exclusionRepository = exclusionRepository;
        this.classifier = classifier;
    }

    /**
     * Âmbito por id de Q&amp;A. Não aparecem no mapa — e o filtro rejeita-os como falha técnica — os
     * ids sem linha, sem pergunta ou com um marcador ou tipo de regra desconhecido (dado inválido).
     */
    @Transactional(readOnly = true)
    public Map<UUID, CandidateScope> load(Collection<UUID> qaIds) {
        Map<UUID, CandidateScope> scopes = new HashMap<>();
        if (qaIds == null || qaIds.isEmpty()) {
            return scopes;
        }
        Map<UUID, Set<ApplicabilityMarker>> exclusions = new HashMap<>();
        Map<UUID, Set<ApplicabilityMarker>> required = new HashMap<>();
        Set<UUID> invalid = new HashSet<>();
        for (KnowledgeQaExclusionMarkerRow row : exclusionRepository.findMarkerRowsByKnowledgeQaIdIn(qaIds)) {
            Optional<ApplicabilityMarker> marker = ApplicabilityMarker.fromCode(row.marker());
            Optional<ApplicabilityRuleKind> kind = ApplicabilityRuleKind.fromCode(row.kind());
            if (marker.isEmpty() || kind.isEmpty()) {
                invalid.add(row.questionAnswerId());
                continue;
            }
            Map<UUID, Set<ApplicabilityMarker>> target =
                    kind.get() == ApplicabilityRuleKind.REQUIRES ? required : exclusions;
            target.computeIfAbsent(row.questionAnswerId(), id -> EnumSet.noneOf(ApplicabilityMarker.class))
                    .add(marker.get());
        }
        if (!invalid.isEmpty()) {
            log.warn("Scope gate: {} Q&A candidate(s) with unknown applicability marker or rule kind; rejected",
                    invalid.size());
        }
        for (KnowledgeQaScopeRow row : repository.findScopeRowsByIdIn(qaIds)) {
            if (invalid.contains(row.id())) {
                continue;
            }
            String question = row.normalizedQuestion() != null && !row.normalizedQuestion().isBlank()
                    ? row.normalizedQuestion() : row.originalQuestion();
            if (question == null || question.isBlank()) {
                continue;
            }
            scopes.put(row.id(), new CandidateScope(
                    classifier.classifyCandidate(row.topic(), row.subtopic(), question),
                    exclusions.getOrDefault(row.id(), Set.of()),
                    required.getOrDefault(row.id(), Set.of())));
        }
        return scopes;
    }
}
