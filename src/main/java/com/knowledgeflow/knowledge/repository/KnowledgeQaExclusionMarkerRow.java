package com.knowledgeflow.knowledge.repository;

import java.util.UUID;

/** Linha só de leitura: um marcador de exclusão efectivo de uma Q&amp;A (para o gate de âmbito). */
public record KnowledgeQaExclusionMarkerRow(UUID questionAnswerId, String marker) {}
