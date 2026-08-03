package com.knowledgeflow.ai.documented;

import com.knowledgeflow.ai.grounding.AnswerSource;
import com.knowledgeflow.ai.grounding.AnswerSupportStatus;
import com.knowledgeflow.ai.grounding.GroundedAIResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Conversor aditivo de {@link GroundedAIResponse} para {@link DocumentedTaxiaAnswer}
 * (tarefa D4, evoluído em D5/D6).
 *
 * <p>Não altera o comportamento do fluxo actual ({@code GroundingService}/{@code /ask});
 * apenas projecta a resposta já produzida no contrato documentado. Responsabilidades
 * separadas:
 * <ul>
 *   <li>{@link SourceAssessmentService} (D5) avalia cada fonte;</li>
 *   <li>{@link AnswerDecisionService} (D6) decide forma/prudência ({@link AnswerType},
 *       {@link ParecerRequirement}, resumos e listas);</li>
 *   <li>este mapper monta o DTO {@link DocumentedTaxiaAnswer}.</li>
 * </ul>
 *
 * <p>A projecção por {@link VisibilityLevel} continua reservada para D7. O
 * {@code aggregatedRiskLevel} mantém-se {@code null} enquanto não houver risco agregado
 * real (regra 27).
 */
@Component
public class DocumentedTaxiaAnswerMapper {

    private final SourceAssessmentService sourceAssessmentService;
    private final AnswerDecisionService answerDecisionService;

    public DocumentedTaxiaAnswerMapper(SourceAssessmentService sourceAssessmentService,
            AnswerDecisionService answerDecisionService) {
        this.sourceAssessmentService = sourceAssessmentService;
        this.answerDecisionService = answerDecisionService;
    }

    /**
     * Converte uma resposta de grounding no contrato documentado.
     *
     * @param question pergunta original recebida
     * @param grounded resposta de grounding (ponto de compatibilidade)
     * @return resposta documentada equivalente
     */
    public DocumentedTaxiaAnswer fromGroundedResponse(String question, GroundedAIResponse grounded) {
        AnswerSupportStatus supportStatus = grounded.supportStatus();

        List<SourceEvidence> sources = mapSources(grounded.sources(), supportStatus);

        AnswerDecision decision = answerDecisionService.decide(
                supportStatus,
                grounded.requiresHumanValidation(),
                sources,
                grounded.limitations(),
                grounded.validationMessage());

        return new DocumentedTaxiaAnswer(
                UUID.randomUUID().toString(),
                question,
                null,
                grounded.answer(),
                grounded.answer(),
                decision.answerType(),
                supportStatus,
                null,
                decision.parecerRequirement(),
                VisibilityLevel.INTERNAL,
                decision.overallFreshnessStatus(),
                decision.confidenceSummary(),
                decision.limitations(),
                List.of(),
                safeList(grounded.missingInformation()),
                decision.sourceSummary(),
                sources,
                decision.warnings(),
                decision.nextSteps(),
                internalDiagnostics(grounded));
    }

    private List<SourceEvidence> mapSources(List<AnswerSource> sources, AnswerSupportStatus supportStatus) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        boolean supportsConclusion = supportStatus == AnswerSupportStatus.SUPPORTED;
        List<SourceEvidence> mapped = new ArrayList<>(sources.size());
        for (AnswerSource source : sources) {
            SourceRole sourceRole = sourceAssessmentService.assessSourceRole(source);
            mapped.add(new SourceEvidence(
                    null,
                    source.title(),
                    null,
                    sourceAssessmentService.assessAuthorityLevel(source),
                    sourceRole,
                    sourceAssessmentService.assessSourceQuality(source),
                    sourceAssessmentService.buildSourceCore(source),
                    sourceAssessmentService.buildSourceDiversityGroup(source),
                    sourceAssessmentService.assessSourceDiversity(source),
                    sourceAssessmentService.assessFreshnessStatus(source),
                    source.reference(),
                    null,
                    true,
                    supportsConclusion,
                    false,
                    false,
                    sourceRole == SourceRole.DERIVATIVE_REPLICATED,
                    List.of(),
                    null,
                    List.of()));
        }
        return List.copyOf(mapped);
    }

    private String internalDiagnostics(GroundedAIResponse grounded) {
        return "supportReason=" + grounded.supportReason()
                + "; provider=" + grounded.provider()
                + "; model=" + grounded.model()
                + "; providerCalled=" + grounded.providerCalled()
                + "; responseRejected=" + grounded.responseRejected()
                + "; unsupportedClaimsCount=" + grounded.unsupportedClaimsCount();
    }

    private List<String> safeList(List<String> list) {
        return list != null ? List.copyOf(list) : List.of();
    }
}
