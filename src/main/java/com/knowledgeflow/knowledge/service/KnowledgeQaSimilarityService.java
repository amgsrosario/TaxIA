package com.knowledgeflow.knowledge.service;

import com.knowledgeflow.knowledge.dto.SimilarQaResult;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds Q&A entries that are lexically similar to a given question.
 * Uses Jaccard-like token overlap as a simple heuristic — no external calls.
 * Embeddings-based similarity can replace this when the indexer is wired.
 */
@Service
public class KnowledgeQaSimilarityService {

    private static final int DEFAULT_CANDIDATES = 200;
    private static final double SIMILARITY_THRESHOLD = 0.25;

    private final KnowledgeQuestionAnswerRepository qaRepository;
    private final KnowledgeQuestionAnswerNormalizer normalizer;

    public KnowledgeQaSimilarityService(
            KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeQuestionAnswerNormalizer normalizer) {
        this.qaRepository = qaRepository;
        this.normalizer = normalizer;
    }

    @Transactional(readOnly = true)
    public List<SimilarQaResult> findSimilar(UUID organizationId, String question, int topK) {
        // Load a candidate pool (recent entries, no filter on status)
        List<KnowledgeQuestionAnswer> candidates =
                qaRepository.findByOrganizationId(
                        organizationId, PageRequest.of(0, DEFAULT_CANDIDATES)).getContent();

        String normQ = normalizer.toComparisonKey(question);
        List<String> queryTokens = tokenize(normQ);

        List<SimilarQaResult> results = new ArrayList<>();
        for (KnowledgeQuestionAnswer qa : candidates) {
            String candidateNorm = normalizer.toComparisonKey(qa.getOriginalQuestion());
            double sim = jaccardSimilarity(queryTokens, tokenize(candidateNorm));
            if (sim >= SIMILARITY_THRESHOLD) {
                boolean conflict = sim >= 0.8
                        && qa.getCurationStatus() == KnowledgeCurationStatus.VALIDATED;
                results.add(new SimilarQaResult(
                        qa.getId(),
                        qa.getOriginalQuestion(),
                        qa.getCurationStatus(),
                        sim,
                        conflict));
            }
        }

        return results.stream()
                .sorted((a, b) -> Double.compare(b.similarity(), a.similarity()))
                .limit(topK > 0 ? topK : 10)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        return List.of(text.split("\\s+"));
    }

    private double jaccardSimilarity(List<String> a, List<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        long intersection = a.stream().filter(b::contains).count();
        long union = a.size() + b.size() - intersection;
        return union == 0 ? 0 : (double) intersection / union;
    }
}
