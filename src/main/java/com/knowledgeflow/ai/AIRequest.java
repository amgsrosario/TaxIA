package com.knowledgeflow.ai;

/**
 * Input to the AI layer.
 *
 * @param taskType      type of task — available for routing and observability
 * @param systemPrompt  system instructions/persona for the model. Providers are domain-neutral:
 *                      when null or blank, no system prompt is sent and the provider injects no
 *                      persona of its own. Any domain persona (e.g. the TaxIA fiscal assistant)
 *                      must be supplied explicitly by the calling layer.
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
