package com.knowledgeflow.knowledge.repository;

import java.util.UUID;

/**
 * Linha só de leitura: uma regra de aplicabilidade efectiva de uma Q&amp;A (para o gate de âmbito) —
 * marcador e tipo ({@code EXCLUDES}/{@code REQUIRES}, ADR-009).
 */
public record KnowledgeQaApplicabilityRuleRow(UUID questionAnswerId, String marker, String kind) {}
