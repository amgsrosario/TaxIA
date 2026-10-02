package com.knowledgeflow.ai.grounding.scope;

import com.knowledgeflow.knowledge.enums.ApplicabilityMarker;
import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Gate de contradição de âmbito (M4-SCOPE) e de aplicabilidade (M4-SCOPE-V2, ADR-004), aplicado
 * candidato a candidato depois do filtro de relevância e antes da avaliação de suficiência, do
 * prompt e das fontes. Um só gate: primeiro as contradições derivadas (imposto, categoria,
 * operação), depois as exclusões declaradas pela Q&amp;A.
 *
 * <p>Candidatos sem Q&amp;A de origem ({@code sourceQaId == null}, caso DOCUMENT) passam sem
 * alteração. Para os restantes, uma falha técnica (classificação ou carregamento), a ausência da
 * linha da Q&amp;A ou um marcador de exclusão inválido rejeita o candidato (fail-closed): nesse caso o pedido acaba em resposta-limite,
 * nunca com fontes de âmbito por verificar. A ordem do RAG é preservada.
 */
@Component
public class FiscalScopeFilter {

    private static final Logger log = LoggerFactory.getLogger(FiscalScopeFilter.class);

    private final CandidateScopeLoader loader;
    private final FiscalScopeClassifier classifier;
    private final ApplicabilityMarkerDetector detector;
    private final ScopeCompatibilityGate gate;
    private final boolean enabled;

    @Autowired
    public FiscalScopeFilter(
            CandidateScopeLoader loader,
            FiscalScopeClassifier classifier,
            ApplicabilityMarkerDetector detector,
            ScopeCompatibilityGate gate,
            ScopeGateProperties properties) {
        this(loader, classifier, detector, gate, properties.enabled());
    }

    private FiscalScopeFilter(
            CandidateScopeLoader loader,
            FiscalScopeClassifier classifier,
            ApplicabilityMarkerDetector detector,
            ScopeCompatibilityGate gate,
            boolean enabled) {
        this.loader = loader;
        this.classifier = classifier;
        this.detector = detector;
        this.gate = gate;
        this.enabled = enabled;
    }

    /** Filtro inactivo (devolve os candidatos tal como recebidos), para construções manuais em testes. */
    public static FiscalScopeFilter disabled() {
        return new FiscalScopeFilter(null, null, null, null, false);
    }

    public List<RetrievedCase> filter(String question, List<RetrievedCase> candidates) {
        if (!enabled || candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }

        Set<UUID> qaIds = new LinkedHashSet<>();
        candidates.stream().map(RetrievedCase::sourceQaId).filter(Objects::nonNull).forEach(qaIds::add);

        FiscalScope queryScope = null;
        Set<ApplicabilityMarker> queryMarkers = Set.of();
        Map<UUID, CandidateScope> candidateScopes = Map.of();
        boolean technicalFailure = false;
        if (!qaIds.isEmpty()) {
            try {
                queryScope = classifier.classify(question);
                queryMarkers = detector.detect(question, queryScope);
                candidateScopes = loader.load(qaIds);
            } catch (RuntimeException e) {
                technicalFailure = true;
                log.warn("Scope gate technical failure ({}); rejecting {} Q&A candidate(s)",
                        e.getClass().getSimpleName(), qaIds.size());
            }
        }

        List<RetrievedCase> kept = new ArrayList<>(candidates.size());
        Map<ScopeReason, Integer> counts = new EnumMap<>(ScopeReason.class);
        for (RetrievedCase candidate : candidates) {
            CandidateScope candidateScope = candidate.sourceQaId() == null
                    ? null : candidateScopes.get(candidate.sourceQaId());
            ScopeDecision decision;
            if (candidate.sourceQaId() == null) {
                decision = ScopeDecision.allow(ScopeReason.NOT_APPLICABLE);
            } else if (technicalFailure || candidateScope == null) {
                decision = ScopeDecision.reject(ScopeReason.TECHNICAL_FAILURE);
            } else {
                decision = gate.decide(queryScope, queryMarkers, candidateScope);
            }
            counts.merge(decision.reason(), 1, Integer::sum);
            if (log.isDebugEnabled()) {
                log.debug("Scope gate candidate: sourceQaId={}, score={}, allowed={}, reason={}, query={}, "
                                + "queryMarkers={}, candidate={}",
                        candidate.sourceQaId(), candidate.similarity(), decision.allowed(), decision.reason(),
                        queryScope, queryMarkers, candidateScope);
            }
            if (decision.allowed()) {
                kept.add(candidate);
            }
        }

        log.info("Scope gate: received={}, kept={}, rejected={}, reasons={}, dictionary={}, applicabilityVocabulary={}",
                candidates.size(), kept.size(), candidates.size() - kept.size(), counts,
                FiscalScopeClassifier.DICTIONARY_VERSION, ApplicabilityMarkerDetector.VOCABULARY_VERSION);
        return List.copyOf(kept);
    }
}
