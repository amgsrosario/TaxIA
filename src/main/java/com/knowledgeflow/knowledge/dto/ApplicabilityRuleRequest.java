package com.knowledgeflow.knowledge.dto;

/**
 * Pedido de nova regra de aplicabilidade: código do vocabulário, justificação opcional (máx. 500) e
 * tipo ({@code EXCLUDES} por omissão; {@code REQUIRES} = contexto obrigatório, ADR-009).
 */
public record ApplicabilityRuleRequest(String marker, String note, String kind) {

    public ApplicabilityRuleRequest(String marker, String note) {
        this(marker, note, null);
    }
}
