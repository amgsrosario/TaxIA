package com.knowledgeflow.research.eligibility;

import com.knowledgeflow.ai.documented.AnswerType;
import com.knowledgeflow.ai.documented.DocumentedTaxiaAnswerMapper;
import com.knowledgeflow.ai.documented.FoundationGovernance;
import com.knowledgeflow.ai.grounding.AnswerSupportStatus;

/**
 * Regra de elegibilidade de uma pergunta do {@code /ask} para investigação (KF-CONV-01, especificação de integração
 * v13, §3.3).
 * <p>
 * Elegível só quando, cumulativamente:
 * <ol>
 *   <li>o modo integrado está ligado e o utilizador é ADMIN;</li>
 *   <li>o {@code /ask} devolveu {@code RESPOSTA_LIMITE} (o que exclui {@code PEDIDO_DE_PARECER});</li>
 *   <li>com {@code supportStatus = INSUFFICIENT_CONTEXT} (o que exclui {@code REQUIRES_HUMAN_REVIEW});</li>
 *   <li>e a resposta não foi suprimida por governação de risco (ADR-007, fundamento {@code CRITICAL}), segundo a
 *       regra existente {@link DocumentedTaxiaAnswerMapper#suppressesGeneratedAnswer(FoundationGovernance)}.</li>
 * </ol>
 * A regra não acrescenta outras condições de produto. Dados em falta dão sempre não elegível (fail closed).
 * <p>
 * Função pura, determinística e sem efeitos. Componente inerte: não está ligada ao {@code /ask}, a nenhum endpoint
 * nem a configuração; a sua utilização depende da aceitação do ADR-010 e de decisão de produto.
 */
public final class ResearchEligibility {

    /** Causa da decisão, pela ordem em que as condições são avaliadas. */
    public enum Reason {
        ELIGIBLE,
        INTEGRATION_DISABLED,
        NOT_ADMIN,
        MISSING_INPUT,
        NOT_LIMIT_ANSWER,
        NOT_INSUFFICIENT_CONTEXT,
        CRITICAL_SUPPRESSION
    }

    /** Decisão de elegibilidade: {@code eligible} é verdadeiro se e só se {@code reason == ELIGIBLE}. */
    public record Decision(boolean eligible, Reason reason) {
        public Decision {
            if (reason == null || eligible != (reason == Reason.ELIGIBLE)) {
                throw new IllegalArgumentException("Decisão incoerente: eligible=" + eligible + ", reason=" + reason);
            }
        }
    }

    private ResearchEligibility() {
    }

    /**
     * @param integrationEnabled modo integrado ligado (configuração, resolvida pelo chamador)
     * @param admin              utilizador autenticado com papel ADMIN (resolvido pelo chamador)
     * @param answerType         tipo de resposta efectivo do {@code /ask}
     * @param supportStatus      estado de suporte do grounding do {@code /ask}
     * @param governance         governação dos fundamentos usados no {@code /ask}
     */
    public static Decision evaluate(boolean integrationEnabled, boolean admin, AnswerType answerType,
            AnswerSupportStatus supportStatus, FoundationGovernance governance) {
        if (!integrationEnabled) return no(Reason.INTEGRATION_DISABLED);
        if (!admin) return no(Reason.NOT_ADMIN);
        if (answerType == null || supportStatus == null || governance == null) return no(Reason.MISSING_INPUT);
        if (answerType != AnswerType.RESPOSTA_LIMITE) return no(Reason.NOT_LIMIT_ANSWER);
        if (supportStatus != AnswerSupportStatus.INSUFFICIENT_CONTEXT) return no(Reason.NOT_INSUFFICIENT_CONTEXT);
        if (DocumentedTaxiaAnswerMapper.suppressesGeneratedAnswer(governance)) return no(Reason.CRITICAL_SUPPRESSION);
        return new Decision(true, Reason.ELIGIBLE);
    }

    private static Decision no(Reason reason) {
        return new Decision(false, reason);
    }
}
