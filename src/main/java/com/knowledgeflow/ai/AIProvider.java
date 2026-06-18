package com.knowledgeflow.ai;

/**
 * Infrastructure contract for AI model providers.
 * Each provider encapsulates its own SDK, authentication, endpoint, and response mapping.
 * The application layer interacts only with this abstraction.
 */
public interface AIProvider {

    String providerId();

    AIResponse generate(AIRequest request);
}
