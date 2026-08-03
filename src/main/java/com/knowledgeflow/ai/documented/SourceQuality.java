package com.knowledgeflow.ai.documented;

/**
 * Força qualitativa da base documental de uma fonte (Decisão C9).
 *
 * <p>Mede força/robustez, não contagem de fontes. Valores conceptuais, sem thresholds
 * nem scoring nesta fase (materialização técnica posterior).
 *
 * <p>Contrato: docs/taxia-documented-answer-contract.md (D3), secção 7.
 */
public enum SourceQuality {

    /** Suporte forte (autoridade elevada, aplicável e actual/estável). */
    STRONG,

    /** Suporte adequado para a finalidade normal da consulta. */
    ADEQUATE,

    /** Suporte limitado; sustenta apenas enquadramento parcial. */
    LIMITED,

    /** Suporte fraco; não sustenta conclusão fiscal actual sozinho. */
    WEAK
}
