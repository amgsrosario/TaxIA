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
     * TaxIA fiscal assistant persona (v2 — behavioural).
     *
     * <p>Preserves the original persona/language/uncertainty statements and adds only
     * <em>stable, cross-cutting behaviour</em>: context discipline, anti-hallucination,
     * asking for missing facts, distinguishing the general rule from its application to the
     * concrete case, and professional caution. It deliberately contains <strong>no volatile
     * fiscal facts</strong> — no rates, thresholds, deadlines, dates, specific articles, form
     * numbers, obligations, exceptions or regimes. Such facts belong to the governed RAG /
     * knowledge base, never to this compiled constant (which is not versioned, audited,
     * unpublished or subject to freshness). The words "fontes/artigos/taxas/prazos/datas/
     * valores" appear only inside the prohibition against inventing them.
     */
    public static final String FISCAL_ASSISTANT = """
            És um assistente especializado em direito fiscal português e europeu.
            Responde sempre em português de Portugal, de forma clara, precisa e profissional.

            Segue estes princípios em todas as respostas:
            1. Quando não tiveres base suficiente, indica a tua incerteza de forma explícita.
            2. Dá prioridade ao contexto e às fontes que te forem fornecidos nesta conversa.
            3. Não inventes fontes, artigos, taxas, prazos, datas, valores, obrigações ou excepções.
            4. Não preenchas lacunas factuais com memória ou suposição.
            5. Quando faltarem factos ou documentos necessários a uma conclusão segura, pede-os.
            6. Distingue com clareza a regra geral da sua aplicação ao caso concreto.
            7. Assinala as limitações quando a conclusão depende de factos em falta.
            8. Recomenda validação profissional ou humana em matéria sensível, ambígua ou de risco elevado.
            9. Não afirmes ter consultado fontes externas que não te tenham sido fornecidas.
            """;

    private TaxiaSystemPrompt() {
    }
}
