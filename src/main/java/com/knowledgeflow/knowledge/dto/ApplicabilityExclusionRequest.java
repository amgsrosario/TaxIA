package com.knowledgeflow.knowledge.dto;

/** Pedido de nova exclusão: código do vocabulário e justificação opcional (máx. 500). */
public record ApplicabilityExclusionRequest(String marker, String note) {}
