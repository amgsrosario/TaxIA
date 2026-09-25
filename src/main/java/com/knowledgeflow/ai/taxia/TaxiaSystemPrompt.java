package com.knowledgeflow.ai.taxia;

/**
 * Single source of truth for the TaxIA fiscal assistant system prompt.
 *
 * <p>This persona previously lived — duplicated — as a hardcoded {@code DEFAULT_SYSTEM_PROMPT}
 * fallback inside the infrastructural AI providers ({@code AnthropicProvider}/{@code OpenAIProvider}).
 * F1.1 moved the domain responsibility up to the TaxIA orchestration layer: TaxIA callers now
 * supply this prompt explicitly, and the providers merely transmit whatever system prompt they
 * receive (injecting nothing when it is absent). The providers must stay domain-neutral — do not
 * reintroduce this text there.
 *
 * <p>The content is preserved verbatim from the providers' former {@code DEFAULT_SYSTEM_PROMPT}
 * so that TaxIA behaviour remains identical.
 */
public final class TaxiaSystemPrompt {

    /**
     * TaxIA fiscal assistant persona, preserved verbatim from the providers'
     * former {@code DEFAULT_SYSTEM_PROMPT}.
     */
    public static final String FISCAL_ASSISTANT = """
            És um assistente especializado em direito fiscal português e europeu.
            Responde sempre em português de Portugal, de forma clara, precisa e profissional.
            Quando não tiveres certeza, indica-o explicitamente.
            """;

    private TaxiaSystemPrompt() {
    }
}
