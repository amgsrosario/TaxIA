package com.knowledgeflow.knowledge.dto;

import jakarta.validation.constraints.Size;

/** Marca/desmarca matéria estável (C8, ADR-008); {@code reason} obrigatória quando {@code stableMatter=true}. */
public record SourceStableMatterRequest(boolean stableMatter, @Size(max = 2000) String reason) {
}
