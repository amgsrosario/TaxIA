package com.knowledgeflow.knowledge.dto;

import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record SourceReferenceResponse(
        UUID id,
        KnowledgeSourceType sourceType,
        String title,
        String legalReference,
        String url,
        UUID documentId,
        UUID fragmentId,
        LocalDate validFrom,
        LocalDate validTo,
        String notes,
        OffsetDateTime createdAt
) {
    public static SourceReferenceResponse from(KnowledgeSourceReference src) {
        return new SourceReferenceResponse(
                src.getId(),
                src.getSourceType(),
                src.getTitle(),
                src.getLegalReference(),
                src.getUrl(),
                src.getDocumentId(),
                src.getFragmentId(),
                src.getValidFrom(),
                src.getValidTo(),
                src.getNotes(),
                src.getCreatedAt());
    }
}
