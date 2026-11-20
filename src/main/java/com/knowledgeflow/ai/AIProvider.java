package com.knowledgeflow.ai;

/**
 * Infrastructure contract for AI model providers.
 * Each provider encapsulates its own SDK, authentication, endpoint, and response mapping.
 * The application layer interacts only with this abstraction.
 */
public interface AIProvider {

    String providerId();

    AIResponse generate(AIRequest request);

    /**
     * O fornecedor factura as chamadas (omissão: {@code true}). No percurso de investigação, um fornecedor facturável é
     * hoje sempre recusado antes do envio ({@code UnfundedModelClient}); só com o futuro controlo financeiro (L6,
     * reservado) poderá ser usado, através de {@link #generateSingleAttempt}.
     */
    default boolean billable() {
        return true;
    }

    /** Suporta uma chamada com exactamente UMA tentativa ao fornecedor (sem retries automáticos). */
    default boolean supportsSingleAttempt() {
        return false;
    }

    /**
     * Uma única tentativa ao fornecedor, sem retries. Só disponível se {@link #supportsSingleAttempt()}. Hoje só o
     * {@code stub} (não facturável) a implementa. No futuro L6 (reservado), cada tentativa facturável terá de ser
     * reservada antes e liquidada depois pelo controlo financeiro.
     */
    default AIResponse generateSingleAttempt(AIRequest request) {
        throw new UnsupportedOperationException("Provider sem chamada de tentativa única: " + providerId());
    }

    /** Modelo efectivamente pedido ao fornecedor (para o futuro cálculo de preço do L6); nulo se desconhecido. */
    default String model() {
        return null;
    }

    /** Limite de tokens de saída enviado em cada pedido ({@code max_tokens}); 0 se desconhecido. */
    default int maxOutputTokens() {
        return 0;
    }
}
