package com.knowledgeflow.ai;

/**
 * Primary AI port. All features that need a model response go through here.
 * Provider selection, error normalisation, and observability are handled by
 * the implementation — callers only know about AIRequest and AIResponse.
 */
public interface AIService {

    AIResponse complete(AIRequest request);
}
