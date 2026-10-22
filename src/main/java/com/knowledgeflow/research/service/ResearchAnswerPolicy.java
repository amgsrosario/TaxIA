package com.knowledgeflow.research.service;

import com.knowledgeflow.ai.documented.AnswerType;
import com.knowledgeflow.ai.documented.ParecerRequirement;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Decisão do tipo de resposta de uma investigação (AR2-DEC-KF-2026-10-22, Aditamento §2).
 * <p>
 * Evidência não promovida nunca dá mais do que {@code CONSULTA_DOCUMENTADA_COM_LIMITACOES} com
 * parecer {@code SUGGESTED}. Conflito material não resolvido ou vigência incerta dão
 * {@code RESPOSTA_LIMITE} com {@code REQUIRED}; insuficiência de fundamento dá
 * {@code RESPOSTA_LIMITE} com {@code SUGGESTED}. A ordem das regras é a da gravidade.
 */
@Component
public class ResearchAnswerPolicy {

    /** Estado da investigação relevante para a decisão. */
    public record Inputs(
            int acceptedEvidence,
            int usablePropositions,
            boolean unresolvedConflict,
            boolean uncertainValidity) {}

    public record Decision(AnswerType answerType, ParecerRequirement parecerRequirement, List<String> reasons) {

        public boolean isLimit() {
            return answerType == AnswerType.RESPOSTA_LIMITE;
        }
    }

    public Decision decide(Inputs in) {
        if (in.acceptedEvidence() == 0) {
            return limit(ParecerRequirement.SUGGESTED,
                    "Insuficiência de fundamento: nenhuma evidência oficial confirmada e aceite.");
        }
        if (in.unresolvedConflict()) {
            return limit(ParecerRequirement.REQUIRED,
                    "Conflito material não resolvido entre proposições extraídas da evidência.");
        }
        if (in.uncertainValidity()) {
            return limit(ParecerRequirement.REQUIRED,
                    "Vigência incerta da evidência que sustentaria a conclusão.");
        }
        if (in.usablePropositions() == 0) {
            return limit(ParecerRequirement.SUGGESTED,
                    "Insuficiência de fundamento: nenhuma regra ou proposição com âncora literal na evidência.");
        }
        return new Decision(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES, ParecerRequirement.SUGGESTED,
                List.of("Fundamentação em evidência oficial de investigação não promovida a conhecimento institucional."));
    }

    /**
     * A validação final do grounding rejeitou a resposta gerada. A razão apresentada é genérica: as
     * afirmações sem suporte nunca são repetidas ao utilizador (ficam só na memória crítica).
     */
    public Decision groundingRejected() {
        return limit(ParecerRequirement.SUGGESTED,
                "A resposta gerada continha afirmações sem suporte na evidência recolhida e foi rejeitada.");
    }

    private static Decision limit(ParecerRequirement parecer, String reason) {
        return new Decision(AnswerType.RESPOSTA_LIMITE, parecer, List.of(reason));
    }
}
