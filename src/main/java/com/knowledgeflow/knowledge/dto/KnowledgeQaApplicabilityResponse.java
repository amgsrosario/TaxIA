package com.knowledgeflow.knowledge.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Estado de aplicabilidade de uma Q&amp;A (só admin/INTERNAL; nunca em DEMO/EXTERNAL): âmbito
 * derivado (só leitura, como o gate o vê), regras de aplicabilidade (exclusões e contexto
 * obrigatório, ADR-009), revisão e vocabulário disponível.
 *
 * <p>{@code exclusions} é um alias legado de {@code rules} (mesma lista, com {@code kind}), mantido
 * para clientes anteriores ao ADR-009; usar {@code rules}.
 */
public record KnowledgeQaApplicabilityResponse(
        UUID questionAnswerId,
        boolean published,
        /** Retirar uma regra fica pendente de validação humana (Q&amp;A publicada ou VALIDATED). */
        boolean removalRequiresValidation,
        OffsetDateTime applicabilityReviewedAt,
        String applicabilityReviewedBy,
        DerivedScope derivedScope,
        List<ApplicabilityRuleResponse> rules,
        List<ApplicabilityMarkerOption> vocabulary
) {
    /** Alias legado de {@link #rules()} (pré-ADR-009). */
    @com.fasterxml.jackson.annotation.JsonProperty("exclusions")
    public List<ApplicabilityRuleResponse> exclusions() {
        return rules;
    }

    public record DerivedScope(List<String> taxDomains, List<String> incomeCategories, List<String> operations) {}
}
