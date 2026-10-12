package com.knowledgeflow.knowledge.repository;

/**
 * Metadados governados de risco de uma Q&amp;A (M4B, ADR-007), lidos em bruto: o nível de risco vem
 * como texto para que um valor ausente ou desconhecido seja reconhecido como tal (fail-closed),
 * nunca convertido silenciosamente.
 */
public interface KnowledgeQaGovernanceRow {

    /** Id como texto (portável entre PostgreSQL e H2 numa query nativa). */
    String getId();

    String getRiskLevel();

    Boolean getRequiresHumanValidation();
}
