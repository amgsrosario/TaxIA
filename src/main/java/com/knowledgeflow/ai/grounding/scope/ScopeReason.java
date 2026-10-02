package com.knowledgeflow.ai.grounding.scope;

/** Motivo de uma {@link ScopeDecision}. */
public enum ScopeReason {
    /** Sem contradição explícita entre a pergunta e a Q&amp;A. */
    NO_CONTRADICTION,
    /** Candidato sem Q&amp;A de origem (caso DOCUMENT): fora do âmbito do gate, mantido. */
    NOT_APPLICABLE,
    /** Impostos sem intersecção. */
    DOMAIN_MISMATCH,
    /** Categoria(s) de rendimentos da pergunta não cobertas pela Q&amp;A. */
    CATEGORY_NOT_COVERED,
    /** Operações sem intersecção. */
    OPERATION_MISMATCH,
    /** A pergunta tem um marcador que a Q&amp;A declara expressamente não cobrir (ADR-004). */
    APPLICABILITY_EXCLUDED,
    /** Falha técnica (classificação, carregamento, metadata em falta ou marcador de exclusão inválido): rejeitado por segurança. */
    TECHNICAL_FAILURE
}
