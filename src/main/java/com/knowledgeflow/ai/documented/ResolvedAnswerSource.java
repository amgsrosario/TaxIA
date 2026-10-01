package com.knowledgeflow.ai.documented;

import com.knowledgeflow.ai.grounding.AnswerSource;
import java.util.UUID;

/**
 * Uma fonte da resposta depois da resolução de curadoria (M3): ou uma fonte documental curada
 * associada à Q&amp;A de origem, ou — quando não há curadoria — a própria fonte do grounding
 * ({@code curated == null}, comportamento anterior).
 *
 * @param origin fonte do grounding que originou esta entrada (Q&amp;A ou documento)
 * @param curated fonte documental curada; {@code null} no fallback
 */
public record ResolvedAnswerSource(AnswerSource origin, CuratedSource curated) {

    public static ResolvedAnswerSource fallback(AnswerSource origin) {
        return new ResolvedAnswerSource(origin, null);
    }

    public static ResolvedAnswerSource curated(AnswerSource origin, CuratedSource curated) {
        return new ResolvedAnswerSource(origin, curated);
    }

    public boolean isCurated() {
        return curated != null;
    }

    /** Fonte documental curada (KnowledgeSourceReference) já lida da base. */
    public record CuratedSource(
            UUID id,
            String sourceType,
            String title,
            String legalReference,
            String url
    ) {}
}
