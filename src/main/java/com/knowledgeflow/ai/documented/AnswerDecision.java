package com.knowledgeflow.ai.documented;

import java.util.List;

/**
 * Decisão de forma/prudência da resposta documentada (tarefa D6).
 *
 * <p>Resultado do {@link AnswerDecisionService}: concentra as escolhas que antes viviam
 * dispersas no {@link DocumentedTaxiaAnswerMapper} ({@link AnswerType},
 * {@link ParecerRequirement}, resumos e listas de prudência). É um DTO interno de
 * diagnóstico/composição — não é projectado por visibilidade (isso fica para D7).
 *
 * <p>Não decide risco agregado nem projecção; {@code overallFreshnessStatus} é a
 * actualidade agregada conservadora da resposta (não confundir com
 * {@code KnowledgeCurationStatus.OUTDATED} — regra 26).
 */
public record AnswerDecision(
        AnswerType answerType,
        ParecerRequirement parecerRequirement,
        String confidenceSummary,
        List<String> limitations,
        List<String> warnings,
        List<String> nextSteps,
        FreshnessStatus overallFreshnessStatus,
        String sourceSummary
) {}
