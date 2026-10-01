package com.knowledgeflow.ai.grounding;

import java.util.UUID;

/**
 * Fonte usada pelo grounding.
 *
 * @param sourceQaId id da Q&amp;A recuperada que originou esta fonte; {@code null} para casos
 *     {@code DOCUMENT} ou fontes sem Q&amp;A. Identificador interno de rastreabilidade.
 */
public record AnswerSource(
        String title,
        String reference,
        double relevanceScore,
        UUID sourceQaId
) {

    /** Fonte sem Q&amp;A de origem. */
    public AnswerSource(String title, String reference, double relevanceScore) {
        this(title, reference, relevanceScore, null);
    }
}
