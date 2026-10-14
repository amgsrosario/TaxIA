package com.knowledgeflow.knowledge.dto;

import com.knowledgeflow.knowledge.entity.KnowledgeQaApplicabilityExclusion;
import com.knowledgeflow.knowledge.enums.ApplicabilityMarker;
import java.time.OffsetDateTime;

/**
 * Regra de aplicabilidade (só admin/INTERNAL): exclusão ou contexto obrigatório ({@code kind}, ADR-009).
 * {@code removalPending}: a remoção foi pedida numa
 * Q&amp;A publicada e aguarda validação humana — a exclusão continua efectiva.
 */
public record ApplicabilityExclusionResponse(
        String marker,
        String label,
        String kind,
        String note,
        OffsetDateTime createdAt,
        String createdBy,
        boolean removalPending,
        OffsetDateTime removalRequestedAt,
        String removalRequestedBy
) {
    public static ApplicabilityExclusionResponse from(KnowledgeQaApplicabilityExclusion e) {
        return new ApplicabilityExclusionResponse(
                e.getMarker(),
                ApplicabilityMarker.fromCode(e.getMarker()).map(ApplicabilityMarker::label).orElse(null),
                e.getKind(),
                e.getNote(),
                e.getCreatedAt(),
                e.getCreatedBy(),
                e.isRemovalPending(),
                e.getRemovalRequestedAt(),
                e.getRemovalRequestedBy());
    }
}
