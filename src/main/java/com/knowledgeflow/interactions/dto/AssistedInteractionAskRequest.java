package com.knowledgeflow.interactions.dto;

import jakarta.validation.constraints.NotBlank;

public record AssistedInteractionAskRequest(@NotBlank String question) {}
