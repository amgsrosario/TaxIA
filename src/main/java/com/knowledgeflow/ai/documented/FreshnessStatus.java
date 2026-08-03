package com.knowledgeflow.ai.documented;

/**
 * Confiança na actualidade/origem temporal do conhecimento que sustenta a resposta
 * (Decisão C8).
 *
 * <p>Não decide sozinho o {@link AnswerType}: gradua a força, os avisos e as limitações.
 * Distinto de {@code KnowledgeCurationStatus.OUTDATED} (estado editorial de curadoria):
 * aqui {@code OUTDATED} classifica a actualidade da fonte na resposta e pode servir de
 * histórico/contraste/alerta, nunca produz silêncio automático.
 *
 * <p>Contrato: docs/taxia-documented-answer-contract.md (D3), secção 7.
 */
public enum FreshnessStatus {

    /** Fonte actual ou validade confirmada; pode sustentar resposta principal. */
    CURRENT,

    /** Fonte antiga sobre matéria estável; sustenta resposta com nota de actualidade. */
    STABLE_BUT_OLD,

    /** Não é possível confirmar se a fonte reflecte o regime actual. */
    UNCERTAIN,

    /** Fonte revogada/caducada/substituída; não sustenta conclusão fiscal actual. */
    OUTDATED
}
