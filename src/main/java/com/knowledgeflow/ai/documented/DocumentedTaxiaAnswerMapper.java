package com.knowledgeflow.ai.documented;

import com.knowledgeflow.ai.grounding.AnswerSource;
import com.knowledgeflow.ai.grounding.AnswerSupportStatus;
import com.knowledgeflow.ai.grounding.GroundedAIResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Conversor mínimo e aditivo de {@link GroundedAIResponse} para
 * {@link DocumentedTaxiaAnswer} (tarefa D4).
 *
 * <p>Aplica <strong>defaults transitórios</strong> — não é o algoritmo definitivo. Não
 * altera o comportamento do fluxo actual ({@code GroundingService}/{@code /ask}); apenas
 * projecta a resposta já produzida no contrato documentado. A decisão fina de
 * {@link AnswerType}/{@link ParecerRequirement} com scoring/thresholds, a avaliação real
 * de fontes (qualidade/núcleo/actualidade) e a projecção por {@link VisibilityLevel}
 * ficam para D5–D7.
 */
@Component
public class DocumentedTaxiaAnswerMapper {

    /**
     * Converte uma resposta de grounding no contrato documentado.
     *
     * @param question pergunta original recebida
     * @param grounded resposta de grounding (ponto de compatibilidade)
     * @return resposta documentada equivalente, com defaults transitórios
     */
    public DocumentedTaxiaAnswer fromGroundedResponse(String question, GroundedAIResponse grounded) {
        AnswerSupportStatus supportStatus = grounded.supportStatus();

        AnswerType answerType = mapAnswerType(supportStatus);
        ParecerRequirement parecerRequirement =
                mapParecerRequirement(supportStatus, grounded.requiresHumanValidation());

        List<SourceEvidence> sources = mapSources(grounded.sources(), supportStatus);

        List<String> limitations = new ArrayList<>(safeList(grounded.limitations()));
        if (supportStatus != AnswerSupportStatus.SUPPORTED) {
            limitations.add("Suporte documental não totalmente confirmado; resposta apresentada com prudência.");
        }

        List<String> warnings = new ArrayList<>();
        if (grounded.requiresHumanValidation()) {
            String message = grounded.validationMessage();
            warnings.add(message != null && !message.isBlank()
                    ? message
                    : "Tema que pode beneficiar de validação por especialista fiscal.");
        }

        List<String> nextSteps = new ArrayList<>();
        if (parecerRequirement != ParecerRequirement.NONE) {
            nextSteps.add("Considerar Pedido de parecer para confirmação da conclusão.");
        }

        String sourceSummary = sources.isEmpty()
                ? "Sem fontes documentais recuperadas para esta resposta."
                : "Suporte documental inicial baseado nas fontes recuperadas.";

        return new DocumentedTaxiaAnswer(
                UUID.randomUUID().toString(),
                question,
                null,
                grounded.answer(),
                grounded.answer(),
                answerType,
                supportStatus,
                null,
                parecerRequirement,
                VisibilityLevel.INTERNAL,
                FreshnessStatus.UNCERTAIN,
                confidenceSummary(supportStatus),
                List.copyOf(limitations),
                List.of(),
                safeList(grounded.missingInformation()),
                sourceSummary,
                sources,
                List.copyOf(warnings),
                List.copyOf(nextSteps),
                internalDiagnostics(grounded));
    }

    private AnswerType mapAnswerType(AnswerSupportStatus supportStatus) {
        if (supportStatus == null) {
            return AnswerType.RESPOSTA_LIMITE;
        }
        return switch (supportStatus) {
            case SUPPORTED -> AnswerType.CONSULTA_DOCUMENTADA;
            case PARTIALLY_SUPPORTED, REQUIRES_HUMAN_REVIEW -> AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES;
            case INSUFFICIENT_CONTEXT, REJECTED_UNSUPPORTED -> AnswerType.RESPOSTA_LIMITE;
        };
    }

    private ParecerRequirement mapParecerRequirement(
            AnswerSupportStatus supportStatus, boolean requiresHumanValidation) {
        ParecerRequirement base;
        if (supportStatus == null) {
            base = ParecerRequirement.SUGGESTED;
        } else {
            base = switch (supportStatus) {
                case SUPPORTED -> ParecerRequirement.NONE;
                case PARTIALLY_SUPPORTED, INSUFFICIENT_CONTEXT, REQUIRES_HUMAN_REVIEW -> ParecerRequirement.SUGGESTED;
                case REJECTED_UNSUPPORTED -> ParecerRequirement.REQUIRED;
            };
        }
        // A necessidade de validação humana nunca deve baixar o encaminhamento abaixo de SUGGESTED.
        if (requiresHumanValidation && base == ParecerRequirement.NONE) {
            return ParecerRequirement.SUGGESTED;
        }
        return base;
    }

    private List<SourceEvidence> mapSources(List<AnswerSource> sources, AnswerSupportStatus supportStatus) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        boolean supportsConclusion = supportStatus == AnswerSupportStatus.SUPPORTED;
        List<SourceEvidence> mapped = new ArrayList<>(sources.size());
        for (AnswerSource source : sources) {
            mapped.add(new SourceEvidence(
                    null,
                    source.title(),
                    null,
                    AuthorityLevel.INTERNAL_CURATED,
                    SourceRole.PRIMARY,
                    SourceQuality.ADEQUATE,
                    null,
                    null,
                    SourceDiversity.MIXED_OR_UNCLEAR,
                    FreshnessStatus.UNCERTAIN,
                    source.reference(),
                    null,
                    true,
                    supportsConclusion,
                    false,
                    false,
                    false,
                    List.of(),
                    null,
                    List.of()));
        }
        return List.copyOf(mapped);
    }

    private String confidenceSummary(AnswerSupportStatus supportStatus) {
        if (supportStatus == null) {
            return "Confiança indeterminada; resposta apresentada com prudência.";
        }
        return switch (supportStatus) {
            case SUPPORTED -> "Resposta com suporte documental adequado (sem garantia absoluta).";
            case PARTIALLY_SUPPORTED -> "Resposta parcialmente suportada; ler com as limitações indicadas.";
            case INSUFFICIENT_CONTEXT -> "Contexto documental insuficiente para uma conclusão segura.";
            case REQUIRES_HUMAN_REVIEW -> "Tema sensível; recomenda-se prudência e eventual parecer.";
            case REJECTED_UNSUPPORTED -> "Resposta gerada não confirmada; sem conclusão fiscal apresentada.";
        };
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
