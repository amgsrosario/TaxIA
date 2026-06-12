package com.knowledgeflow.ai;

/**
 * Output from the AI layer.
 *
 * @param content     the model's answer text
 * @param modelUsed   identifier of the model/provider that generated the answer
 * @param inputTokens approximate input token count (0 if unknown)
 * @param outputTokens approximate output token count (0 if unknown)
 */
public record AIResponse(String content, String modelUsed, int inputTokens, int outputTokens) {
}
