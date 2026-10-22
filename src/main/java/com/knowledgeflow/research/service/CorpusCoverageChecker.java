package com.knowledgeflow.research.service;

import com.knowledgeflow.ai.grounding.GroundingProperties;
import com.knowledgeflow.ai.grounding.scope.FiscalScopeFilter;
import com.knowledgeflow.rag.RagSearchService;
import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Verifica se o corpus publicado já tem Q&amp;A relevante para a pergunta, com os mesmos critérios do
 * {@code /ask}: limiar de relevância do grounding e gate de âmbito. Só sem Q&amp;A relevante se abre
 * investigação (AR2-DEC-KF-2026-10-22 §8).
 */
@Component
public class CorpusCoverageChecker {

    public enum Coverage { RELEVANT_QA_FOUND, NO_RELEVANT_QA, UNAVAILABLE }

    public record Result(Coverage coverage, List<String> relevantTitles, String detail) {}

    private final RagSearchService ragSearchService;
    private final FiscalScopeFilter scopeFilter;
    private final GroundingProperties groundingProperties;

    public CorpusCoverageChecker(RagSearchService ragSearchService, FiscalScopeFilter scopeFilter,
            GroundingProperties groundingProperties) {
        this.ragSearchService = ragSearchService;
        this.scopeFilter = scopeFilter;
        this.groundingProperties = groundingProperties;
    }

    public Result check(UUID organizationId, String question) {
        List<RetrievedCase> retrieved;
        try {
            retrieved = ragSearchService.findSimilar(organizationId, question);
        } catch (RuntimeException e) {
            return new Result(Coverage.UNAVAILABLE, List.of(),
                    "Verificação do corpus indisponível: " + e.getClass().getSimpleName());
        }
        double threshold = groundingProperties.minimumRelevanceScore();
        List<RetrievedCase> relevant = scopeFilter.filter(question, retrieved.stream()
                .filter(c -> Double.isFinite(c.similarity()) && c.similarity() >= threshold)
                .toList());
        List<String> titles = relevant.stream().map(RetrievedCase::title).distinct().toList();
        if (titles.isEmpty()) {
            return new Result(Coverage.NO_RELEVANT_QA, List.of(),
                    "Sem Q&A publicada relevante (recuperadas=%d, limiar=%s)".formatted(retrieved.size(), threshold));
        }
        return new Result(Coverage.RELEVANT_QA_FOUND, titles,
                "Q&A publicada relevante: " + String.join("; ", titles));
    }
}
