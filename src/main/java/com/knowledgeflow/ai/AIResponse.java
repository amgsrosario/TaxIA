package com.knowledgeflow.ai;

/**
 * Output from the AI layer.
 *
 * @param provider      identifier of the provider that handled the request (e.g. "anthropic")
 * @param modelUsed     identifier of the model that generated the answer
 * @param content       the model's answer text
 * @param inputTokens   input token count (0 if unknown)
 * @param outputTokens  output token count (0 if unknown)
 * @param durationMillis wall-clock time of the provider call in milliseconds (0 if unknown)
 */
public record AIResponse(
        String provider,
        String modelUsed,
        String content,
        int inputTokens,
        int outputTokens,
        long durationMillis
) {}
