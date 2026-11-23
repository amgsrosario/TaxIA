package com.knowledgeflow.ai;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Input to the AI layer.
 *
 * @param taskType      type of task — available for routing and observability
 * @param systemPrompt  system instructions/persona for the model. Providers are domain-neutral:
 *                      when null or blank, no system prompt is sent and the provider injects no
 *                      persona of its own. Any domain persona (e.g. the TaxIA fiscal assistant)
 *                      must be supplied explicitly by the calling layer.
 * @param userMessage   the question or instruction from the user
 * @param outputSchema  optional JSON Schema for the response (structured outputs). Providers that
 *                      support constrained decoding send it; others ignore it. Null when the answer is
 *                      free text. The caller must still validate the answer: the schema is not a
 *                      substitute for strict parsing (truncation and refusals can break it).
 */
public record AIRequest(AITaskType taskType, String systemPrompt, String userMessage, JsonNode outputSchema) {

    public AIRequest(AITaskType taskType, String systemPrompt, String userMessage) {
        this(taskType, systemPrompt, userMessage, null);
    }

    public AIRequest(String systemPrompt, String userMessage) {
        this(AITaskType.STANDARD_RESPONSE, systemPrompt, userMessage);
    }

    public AIRequest(String userMessage) {
        this(AITaskType.STANDARD_RESPONSE, null, userMessage);
    }
}
