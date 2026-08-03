package com.knowledgeflow.ai.documented;

import java.util.List;

/**
 * Projecção da resposta documentada por nível de visibilidade (contrato D3, secção 6).
 *
 * <p>DTO preparado para D7. Nesta fase (D4) <em>não</em> há {@code AnswerProjectionService}
 * — a projecção não é implementada aqui para não misturar decisão e projecção (regra 21).
 *
 * <p>A projecção transforma apenas a apresentação: não decide {@link AnswerType}, não
 * esconde limitações relevantes e não reduz a qualidade em {@code EXTERNAL}/{@code DEMO}.
 */
public record AnswerProjection(
        VisibilityLevel targetVisibilityLevel,
        String visibleAnswer,
        List<SourceEvidence> visibleSources,
        List<String> visibleWarnings,
        List<String> visibleLimitations,
        ParecerRequirement visibleParecerRequirement,
        List<String> hiddenDiagnostics,
        List<String> projectionRulesApplied
) {}
