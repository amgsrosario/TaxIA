package com.knowledgeflow.interactions.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Optional overrides when promoting an assisted interaction to a formal KnowledgeCase.
 * If title/question are omitted, they are taken from the interaction itself.
 */
public record AssistedInteractionPromoteRequest(
        String title,
        @NotBlank String question,
        String content
) {}
