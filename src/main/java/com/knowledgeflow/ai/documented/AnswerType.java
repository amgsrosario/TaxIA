package com.knowledgeflow.ai.documented;

/**
 * Forma da resposta documentada da TaxIA ao utilizador (Decisão C5).
 *
 * <p>Campo central do contrato, num nível mais alto que {@code supportStatus}: define
 * <em>como</em> a TaxIA responde, não apenas o suporte técnico. Uma
 * {@code RESPOSTA_LIMITE} continua a ser uma resposta de pleno direito (não é erro) e
 * {@code PEDIDO_DE_PARECER} é encaminhamento estrutural para o circuito humano (não é
 * falha da resposta automática).
 *
 * <p>Contrato: docs/taxia-documented-answer-contract.md (D3), secção 7.
 */
public enum AnswerType {

    /** Consulta documentada com fundamentação legal normal. */
    CONSULTA_DOCUMENTADA,

    /** Consulta documentada com condições, exclusões e limites reforçados. */
    CONSULTA_DOCUMENTADA_COM_LIMITACOES,

    /** Último patamar automático: só há enquadramento, sem conclusão aplicável segura. */
    RESPOSTA_LIMITE,

    /** Sai do circuito automático: encaminhamento para intervenção humana. */
    PEDIDO_DE_PARECER
}
