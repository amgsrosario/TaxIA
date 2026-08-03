package com.knowledgeflow.ai.documented;

import java.util.List;

/**
 * Projecção da resposta documentada por nível de visibilidade (contrato D3, secção 6).
 *
 * <p>Produzido pelo {@link AnswerProjectionService} (D7). A projecção transforma apenas a
 * apresentação: não decide nem recalcula {@link AnswerType}/{@link ParecerRequirement}
 * (isso é da responsabilidade do {@link AnswerDecisionService}, D6), não esconde limitações
 * relevantes e não reduz a qualidade em {@code EXTERNAL}/{@code DEMO}.
 *
 * <p>{@code visibleAnswerType} preserva (por cópia) a forma já decidida a montante — está
 * documentada como visível em todos os níveis (contrato §4) e nunca é recalculada aqui
 * (regra 25).
 */
public record AnswerProjection(
        VisibilityLevel targetVisibilityLevel,
        AnswerType visibleAnswerType,
        String visibleAnswer,
        List<SourceEvidence> visibleSources,
        List<String> visibleWarnings,
        List<String> visibleLimitations,
        ParecerRequirement visibleParecerRequirement,
        List<String> hiddenDiagnostics,
        List<String> projectionRulesApplied
) {}
