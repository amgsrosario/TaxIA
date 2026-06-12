package com.knowledgeflow.ai;

/**
 * Input to the AI layer. Intentionally simple — context/RAG enrichment
 * is applied by the AIService implementation before calling the model.
 *
 * @param systemPrompt  instructions/persona for the model (may be null for default)
 * @param userMessage   the question or instruction from the user
 */
public record AIRequest(String systemPrompt, String userMessage) {

    public AIRequest(String userMessage) {
        this(null, userMessage);
    }
}
