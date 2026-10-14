package com.knowledgeflow.knowledge.dto;

/**
 * Entrada do vocabulário de aplicabilidade para o backoffice. {@code requirable}: pode ser contexto
 * obrigatório (ADR-009).
 */
public record ApplicabilityMarkerOption(String code, String label, boolean requirable) {}
