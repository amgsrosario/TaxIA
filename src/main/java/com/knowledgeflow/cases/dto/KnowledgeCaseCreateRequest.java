package com.knowledgeflow.cases.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record KnowledgeCaseCreateRequest(
        @NotNull UUID clientId,
        @NotBlank @Size(max = 240) String title,
        @NotBlank @Size(max = 10000) String question,
        @Size(max = 50000) String content
) {
}
