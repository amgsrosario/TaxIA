package com.knowledgeflow.ai.spend;

import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;

/**
 * Porta das chamadas ao modelo com controlo financeiro obrigatório (KF-M02/CORR-05). O percurso
 * Research usa exclusivamente esta porta; nunca o {@code AIService} genérico (que tem retries).
 */
public interface MeteredModelClient {

    /**
     * Uma única tentativa, reservada antes e liquidada depois.
     *
     * @throws SpendRefusedException se o controlo financeiro recusar (o pedido não é enviado)
     */
    AIResponse complete(AIRequest request);
}
