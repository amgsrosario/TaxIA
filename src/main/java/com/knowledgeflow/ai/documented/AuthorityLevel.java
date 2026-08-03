package com.knowledgeflow.ai.documented;

/**
 * Autoridade de uma fonte, orientando a hierarquia documental (Decisão C9).
 *
 * <p>Fontes externas não oficiais não sustentam sozinhas uma conclusão fiscal actual.
 * Valores conceptuais, sem thresholds nem ranking nesta fase.
 *
 * <p>Contrato: docs/taxia-documented-answer-contract.md (D3), secção 7.
 */
public enum AuthorityLevel {

    /** Legislação. */
    LEGAL,

    /** Orientação administrativa oficial (ofícios circulados, instruções). */
    OFFICIAL_ADMINISTRATIVE,

    /** FAQ oficial. */
    OFFICIAL_FAQ,

    /** Jurisprudência. */
    JURISPRUDENCE,

    /** Fonte oficial complementar. */
    OFFICIAL_COMPLEMENTARY,

    /** Conhecimento interno curado e validado. */
    INTERNAL_CURATED,

    /** Fonte externa não oficial. */
    EXTERNAL_NON_OFFICIAL
}
