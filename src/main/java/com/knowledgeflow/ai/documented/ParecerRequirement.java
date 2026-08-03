package com.knowledgeflow.ai.documented;

/**
 * Grau de recomendação/encaminhamento para Pedido de parecer (Decisões C3/C6).
 *
 * <p>Não representa uma fila de revisão humana invisível do circuito automático; gradua
 * apenas o encaminhamento naquela resposta concreta. O Pedido de parecer é estrutural e
 * está sempre disponível, seja qual for este valor.
 *
 * <p>Contrato: docs/taxia-documented-answer-contract.md (D3), secção 7.
 */
public enum ParecerRequirement {

    /** Resposta automática suficiente para a finalidade normal; sem encaminhamento especial. */
    NONE,

    /** A TaxIA sugere o Pedido de parecer como opção prudente. */
    SUGGESTED,

    /** Não fechar a conclusão; encaminhar para Pedido de parecer. */
    REQUIRED
}
