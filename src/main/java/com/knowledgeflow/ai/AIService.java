package com.knowledgeflow.ai;

/**
 * Primary AI port. All features that need a model response go through here.
 * Implementations are swappable (Anthropic, OpenAI, local, stub).
 */
public interface AIService {

    /**
     * Send a request to the underlying model and return its response.
     * Implementations are responsible for timeout, retry, and mapping
     * provider-specific errors to {@link com.knowledgeflow.common.error.BusinessException}.
     */
    AIResponse complete(AIRequest request);
}
