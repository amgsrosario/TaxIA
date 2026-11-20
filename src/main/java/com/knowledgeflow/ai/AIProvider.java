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
     * O fornecedor factura as chamadas. Um fornecedor facturável só pode ser usado em percursos com
     * controlo financeiro através de {@link #generateSingleAttempt} (KF-M02/CORR-05).
     */
    default boolean billable() {
        return true;
    }

    /** Suporta uma chamada com exactamente UMA tentativa ao fornecedor (sem retries automáticos). */
    default boolean supportsSingleAttempt() {
        return false;
    }

    /**
     * Uma única tentativa ao fornecedor, sem retries: cada tentativa facturável tem de ser reservada e
     * liquidada pelo controlo financeiro. Só disponível se {@link #supportsSingleAttempt()}.
     */
    default AIResponse generateSingleAttempt(AIRequest request) {
        throw new UnsupportedOperationException("Provider sem chamada de tentativa única: " + providerId());
    }

    /** Modelo efectivamente pedido ao fornecedor (para o preço); nulo se desconhecido. */
    default String model() {
        return null;
    }

    /** Limite de tokens de saída enviado em cada pedido ({@code max_tokens}); 0 se desconhecido. */
    default int maxOutputTokens() {
        return 0;
    }
}
