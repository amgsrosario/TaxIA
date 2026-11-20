package com.knowledgeflow.research.projection;

import com.knowledgeflow.ai.documented.AnswerType;
import com.knowledgeflow.research.entity.ResearchEvidence;
import com.knowledgeflow.research.entity.ResearchInvestigation;
import com.knowledgeflow.research.enums.InvestigationStatus;
import com.knowledgeflow.research.enums.InvestigationTrigger;
import com.knowledgeflow.research.projection.ResearchAnswerProjection.AnswerKind;
import com.knowledgeflow.research.projection.ResearchAnswerProjection.EvidenceSummary;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Constrói a {@link ResearchAnswerProjection} a partir de uma investigação e das suas evidências, segundo a tabela
 * de nulabilidade por estado da especificação de integração KF-CONV-01 v13, §3.6.
 * <p>
 * Função pura, sem acesso à base nem efeitos. Recusa (fail closed) estados que a especificação e a base não
 * admitem, em vez de os projectar.
 */
public final class ResearchAnswerProjector {

    /** Mensagem genérica de falha: a causa técnica ({@code failure_reason}) nunca é exposta. */
    public static final String GENERIC_FAILURE = "A investigação não pôde ser concluída. Tente novamente mais tarde.";

    /** Aviso presente em cada evidência: nenhuma evidência de investigação é conhecimento institucional. */
    public static final String NOT_PROMOTED_NOTICE =
            "Evidência de investigação não promovida a conhecimento institucional.";

    private ResearchAnswerProjector() {
    }

    /**
     * @param investigation  investigação (obrigatória)
     * @param evidence       evidências da investigação; usadas só com {@code COMPLETED}
     * @param coverageTitles títulos do conteúdo validado encontrado na verificação do corpus; usados só com
     *                       {@code NOT_STARTED} por {@code RELEVANT_QA_EXISTS}
     */
    public static ResearchAnswerProjection project(ResearchInvestigation investigation,
            List<ResearchEvidence> evidence, List<String> coverageTitles) {
        Objects.requireNonNull(investigation, "investigation");
        InvestigationStatus status = Objects.requireNonNull(investigation.getStatus(), "status");
        List<ResearchEvidence> ev = evidence == null ? List.of() : evidence;
        List<String> titles = coverageTitles == null ? List.of() : coverageTitles;

        return switch (status) {
            case OPEN -> empty(investigation, null, List.of(), null);
            case FAILED -> empty(investigation, null, List.of(), GENERIC_FAILURE);
            case NOT_STARTED -> {
                InvestigationTrigger reason = investigation.getTriggerReason();
                if (reason != InvestigationTrigger.RELEVANT_QA_EXISTS
                        && reason != InvestigationTrigger.CORPUS_CHECK_UNAVAILABLE) {
                    throw new IllegalStateException("Investigação NOT_STARTED com causa inadmissível: " + reason);
                }
                yield empty(investigation, reason,
                        reason == InvestigationTrigger.RELEVANT_QA_EXISTS ? clean(titles) : List.of(), null);
            }
            case COMPLETED -> completed(investigation, ev);
        };
    }

    private static ResearchAnswerProjection completed(ResearchInvestigation i, List<ResearchEvidence> evidence) {
        AnswerType type = i.getAnswerType();
        AnswerKind kind;
        if (type == AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES) {
            kind = AnswerKind.FUNDAMENTADA;
        } else if (type == AnswerType.RESPOSTA_LIMITE) {
            kind = AnswerKind.TEXTO_LIMITE;
        } else {
            throw new IllegalStateException("Investigação concluída com tipo de resposta inadmissível: " + type);
        }
        if (i.getParecerRequirement() == null) {
            throw new IllegalStateException("Investigação concluída sem exigência de parecer");
        }
        for (ResearchEvidence e : evidence) {
            if (!i.getId().equals(e.getInvestigationId())) {
                throw new IllegalArgumentException("Evidência de outra investigação: " + e.getId());
            }
        }
        return new ResearchAnswerProjection(i.getId(), InvestigationStatus.COMPLETED, null, List.of(), type,
                i.getParecerRequirement(), kind, i.getAnswerText(), lines(i.getLimitations()),
                lines(i.getAssumptions()), lines(i.getDecisionReasons()),
                evidence.stream().map(ResearchAnswerProjector::summary).toList(), null);
    }

    private static ResearchAnswerProjection empty(ResearchInvestigation i, InvestigationTrigger notStartedReason,
            List<String> coverageTitles, String failure) {
        return new ResearchAnswerProjection(i.getId(), i.getStatus(), notStartedReason, coverageTitles, null, null,
                null, null, List.of(), List.of(), List.of(), List.of(), failure);
    }

    private static EvidenceSummary summary(ResearchEvidence e) {
        return new EvidenceSummary(e.getLegalReference(), e.getAuthority(), e.getUrl(), e.getRetrievedAt(),
                e.getContentSha256(), e.getValidityStatus(), e.getClassification(), e.getClassificationReason(),
                NOT_PROMOTED_NOTICE);
    }

    private static List<String> clean(List<String> titles) {
        return titles.stream().filter(Objects::nonNull).map(String::strip).filter(t -> !t.isEmpty()).distinct()
                .toList();
    }

    /** Mesma regra de {@code ResearchTraceView}: uma linha por elemento, sem linhas em branco. */
    static List<String> lines(String text) {
        return text == null || text.isBlank() ? List.of()
                : Arrays.stream(text.split("\n")).filter(l -> !l.isBlank()).toList();
    }
}
