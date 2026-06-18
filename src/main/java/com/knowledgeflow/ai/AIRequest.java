package com.knowledgeflow.ai;

/**
 * Input to the AI layer.
 *
 * @param taskType      type of task — available for routing and observability
 * @param systemPrompt  instructions/persona for the model (may be null for default)
 * @param userMessage   the question or instruction from the user
 */
public record AIRequest(AITaskType taskType, String systemPrompt, String userMessage) {

    public AIRequest(String systemPrompt, String userMessage) {
        this(AITaskType.STANDARD_RESPONSE, systemPrompt, userMessage);
    }

    public AIRequest(String userMessage) {
        this(AITaskType.STANDARD_RESPONSE, null, userMessage);
    }
}
