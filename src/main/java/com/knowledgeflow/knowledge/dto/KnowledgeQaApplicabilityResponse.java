package com.knowledgeflow.knowledge.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Estado de aplicabilidade de uma Q&amp;A (só admin/INTERNAL; nunca em DEMO/EXTERNAL): âmbito
 * derivado (só leitura, como o gate o vê), exclusões, revisão e vocabulário disponível.
 */
public record KnowledgeQaApplicabilityResponse(
        UUID questionAnswerId,
        boolean published,
        /** Retirar uma exclusão fica pendente de validação humana (Q&amp;A publicada ou VALIDATED). */
        boolean removalRequiresValidation,
        OffsetDateTime applicabilityReviewedAt,
        String applicabilityReviewedBy,
        DerivedScope derivedScope,
        List<ApplicabilityExclusionResponse> exclusions,
        List<ApplicabilityMarkerOption> vocabulary
) {
    public record DerivedScope(List<String> taxDomains, List<String> incomeCategories, List<String> operations) {}
}
