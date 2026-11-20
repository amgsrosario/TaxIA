package com.knowledgeflow.ai.spend;

/** Chamada facturável recusada pelo controlo financeiro: o pedido NÃO foi enviado ao fornecedor. */
public class SpendRefusedException extends RuntimeException {

    public SpendRefusedException(String message) {
        super(message);
    }
}
