package com.knowledgeflow.knowledge.dto;

import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

public record SourceReferenceRequest(
        @NotNull KnowledgeSourceType sourceType,
        @NotBlank @Size(max = 500) String title,
        @Size(max = 500) String legalReference,
        @Size(max = 2000) String url,
        UUID documentId,
        UUID fragmentId,
        LocalDate validFrom,
        LocalDate validTo,
        @Size(max = 4000) String notes
) {
}
