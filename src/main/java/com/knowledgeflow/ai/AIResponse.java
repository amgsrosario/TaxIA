package com.knowledgeflow.ai;

/**
 * Output from the AI layer.
 *
 * @param provider      identifier of the provider that handled the request (e.g. "anthropic")
 * @param modelUsed     identifier of the model that generated the answer
 * @param content       the model's answer text
 * @param inputTokens   input token count reported by the provider API;
 *                      0 when the provider did not include usage metrics in the response
 *                      (not the same as the model processing zero tokens)
 * @param outputTokens  output token count reported by the provider API;
 *                      0 when the provider did not include usage metrics in the response
 * @param durationMillis wall-clock time of the provider call in milliseconds (0 if unknown)
 * @param stopReason    why generation stopped, as reported by the provider (e.g. "end_turn",
 *                      "max_tokens", "refusal"); null when the provider does not report it
 */
public record AIResponse(
        String provider,
        String modelUsed,
        String content,
        int inputTokens,
        int outputTokens,
        long durationMillis,
        String stopReason
) {

    public AIResponse(String provider, String modelUsed, String content, int inputTokens, int outputTokens,
            long durationMillis) {
        this(provider, modelUsed, content, inputTokens, outputTokens, durationMillis, null);
    }
}
