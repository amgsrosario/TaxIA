package com.knowledgeflow.research.projection;

import com.knowledgeflow.ai.documented.AnswerType;
import com.knowledgeflow.ai.documented.ParecerRequirement;
import com.knowledgeflow.research.enums.EvidenceClassification;
import com.knowledgeflow.research.enums.InvestigationStatus;
import com.knowledgeflow.research.enums.InvestigationTrigger;
import com.knowledgeflow.research.enums.ValidityStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Projecção de uma investigação para o ecrã «Perguntar» (KF-CONV-01, especificação de integração v13, §3.6).
 * <p>
 * Contém só o que o circuito integrado pode mostrar. <b>Nunca</b> contém hipóteses do modelo, passos, memória
 * crítica, proposições, relações, fornecedor, modelo, tokens, custos, texto integral da evidência nem excepções:
 * esses dados ficam no rasto integral ({@code ResearchTraceView}), reservado a ADMIN.
 * <p>
 * Componente inerte: não está ligado a nenhum endpoint nem configuração. A sua exposição depende da aceitação do
 * ADR-010 e de decisão de produto.
 *
 * @param investigationId     identificador da investigação
 * @param status              estado da investigação
 * @param notStartedReason    só com {@code NOT_STARTED}: a causa ({@code RELEVANT_QA_EXISTS} ou
 *                            {@code CORPUS_CHECK_UNAVAILABLE}); senão {@code null}
 * @param coverageTitles      só com {@code NOT_STARTED}/{@code RELEVANT_QA_EXISTS}: títulos do conteúdo validado
 *                            encontrado; senão vazia
 * @param answerType          só com {@code COMPLETED}
 * @param parecerRequirement  só com {@code COMPLETED}
 * @param answerKind          só com {@code COMPLETED}
 * @param answer              só com {@code COMPLETED}
 * @param limitations         só com {@code COMPLETED} (pode ser vazia)
 * @param assumptions         só com {@code COMPLETED} (pode ser vazia)
 * @param decisionReasons     só com {@code COMPLETED} (pode ser vazia)
 * @param evidence            só com {@code COMPLETED}: todas as evidências, com a classificação
 * @param failure             só com {@code FAILED}: mensagem genérica, nunca a causa técnica
 */
public record ResearchAnswerProjection(
        UUID investigationId,
        InvestigationStatus status,
        InvestigationTrigger notStartedReason,
        List<String> coverageTitles,
        AnswerType answerType,
        ParecerRequirement parecerRequirement,
        AnswerKind answerKind,
        String answer,
        List<String> limitations,
        List<String> assumptions,
        List<String> decisionReasons,
        List<EvidenceSummary> evidence,
        String failure) {

    /** Natureza do texto devolvido numa investigação concluída. */
    public enum AnswerKind {
        /** {@code CONSULTA_DOCUMENTADA_COM_LIMITACOES}: texto composto pelo sistema e aceite pelas barreiras. */
        FUNDAMENTADA,
        /** {@code RESPOSTA_LIMITE}: texto de resposta-limite do sistema. */
        TEXTO_LIMITE
    }

    /**
     * Resumo de uma evidência. O {@code contentSha256} é o SHA-256 do texto do artigo extraído, não da página HTTP.
     * O texto integral não é incluído.
     */
    public record EvidenceSummary(
            String legalReference,
            String authority,
            String url,
            OffsetDateTime retrievedAt,
            String contentSha256,
            ValidityStatus validityStatus,
            EvidenceClassification classification,
            String classificationReason,
            String notice) {}

    public ResearchAnswerProjection {
        coverageTitles = List.copyOf(coverageTitles);
        limitations = List.copyOf(limitations);
        assumptions = List.copyOf(assumptions);
        decisionReasons = List.copyOf(decisionReasons);
        evidence = List.copyOf(evidence);
    }
}
