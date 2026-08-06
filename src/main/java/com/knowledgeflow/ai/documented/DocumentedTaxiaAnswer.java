package com.knowledgeflow.ai.documented;

import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import java.util.List;

/**
 * Resposta documentada da TaxIA (contrato D3, secção 4).
 *
 * <p>Camada aditiva acima de {@code GroundedAIResponse}, que continua a ser o ponto de
 * compatibilidade. Nesta fase (D4) é construída pelo {@link DocumentedTaxiaAnswerMapper}
 * a partir de uma resposta de grounding; a decisão fina (scoring, ranking, thresholds) e
 * a projecção por {@link VisibilityLevel} ficam para fases posteriores (D5–D7).
 *
 * <p>Notas de contrato:
 * <ul>
 *   <li>{@code aggregatedRiskLevel} reutiliza {@link KnowledgeRiskLevel} mas representa o
 *       risco <em>agregado da resposta</em> (máximo dos fundamentos usados — C4); não é o
 *       {@code riskLevel} persistido da entidade Knowledge QA. Pode ser {@code null}
 *       enquanto não houver base clara.</li>
 *   <li>{@code internalDiagnostics} ({@link InternalDiagnostics}, D11) descreve o caminho
 *       técnico até à conclusão e é apenas para {@code INTERNAL}/{@code CURATION_ONLY}
 *       (bastidores C7); {@code EXTERNAL}/{@code DEMO} nunca o expõem. Só existe em runtime,
 *       nunca é persistido.</li>
 * </ul>
 */
public record DocumentedTaxiaAnswer(
        String answerId,
        String question,
        String normalizedQuestion,
        String shortAnswer,
        String technicalAnswer,
        AnswerType answerType,
        com.knowledgeflow.ai.grounding.AnswerSupportStatus supportStatus,
        KnowledgeRiskLevel aggregatedRiskLevel,
        ParecerRequirement parecerRequirement,
        VisibilityLevel visibilityLevel,
        FreshnessStatus freshnessStatus,
        String confidenceSummary,
        List<String> limitations,
        List<String> assumptions,
        List<String> missingFacts,
        String sourceSummary,
        List<SourceEvidence> sources,
        List<String> warnings,
        List<String> nextSteps,
        InternalDiagnostics internalDiagnostics
) {}
