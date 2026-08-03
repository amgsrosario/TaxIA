package com.knowledgeflow.ai.documented;

import java.util.List;

/**
 * Evidência/fonte de uma resposta documentada (contrato D3, secção 5).
 *
 * <p>Estrutura rica proposta como evolução da vista simples {@code AnswerSource}. Vários
 * campos são de diagnóstico ({@code usedInAnswer}, {@code sourceCore}, flags de suporte,
 * {@code notesInternal}) e destinam-se a {@code INTERNAL}/{@code CURATION_ONLY}; a
 * projecção por visibilidade fica para D7.
 *
 * <p>Nesta fase (D4) os campos são preenchidos por defaults transitórios no
 * {@link DocumentedTaxiaAnswerMapper}; não há algoritmo de qualidade/núcleo/actualidade.
 */
public record SourceEvidence(
        String sourceId,
        String title,
        String sourceType,
        AuthorityLevel authorityLevel,
        SourceRole sourceRole,
        SourceQuality sourceQuality,
        String sourceCore,
        String sourceDiversityGroup,
        SourceDiversity sourceDiversity,
        FreshnessStatus freshnessStatus,
        String legalReference,
        String url,
        boolean usedInAnswer,
        boolean supportsConclusion,
        boolean supportsLimitation,
        boolean supportsWarning,
        boolean derivativeOrReplicated,
        List<String> relatedSources,
        String excerpt,
        List<String> notesInternal
) {}
