package com.knowledgeflow.ai.documented;

/**
 * Papel de uma fonte na fundamentação da resposta (Decisão C9).
 *
 * <p>Fontes derivadas/replicadas reproduzem o mesmo núcleo material e não contam como
 * confirmações independentes (eco documental).
 *
 * <p>Contrato: docs/taxia-documented-answer-contract.md (D3), secção 7.
 */
public enum SourceRole {

    /** Sustenta directamente a resposta (autoridade forte, aplicável e actual/estável). */
    PRIMARY,

    /** Acrescenta fundamento materialmente diferente, detalhe, excepção ou contexto. */
    COMPLEMENTARY,

    /** Reproduz/resume/reformula o mesmo núcleo material; não é confirmação independente. */
    DERIVATIVE_REPLICATED
}
