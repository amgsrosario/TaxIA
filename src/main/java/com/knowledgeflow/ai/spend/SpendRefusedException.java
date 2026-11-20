package com.knowledgeflow.ai.spend;

/**
 * Chamada ao modelo recusada antes do envio: o pedido NÃO foi enviado ao fornecedor.
 * <p>
 * Implementação actual: lançada por {@link UnfundedModelClient}, que recusa todos os fornecedores facturáveis por não
 * existir autoridade financeira (L6 reservado). No contrato futuro do L6, será também a recusa da autoridade financeira
 * (envelope esgotado, autoridade indisponível ou não verificada).
 */
public class SpendRefusedException extends RuntimeException {

    public SpendRefusedException(String message) {
        super(message);
    }
}
