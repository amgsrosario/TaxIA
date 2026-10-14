package com.knowledgeflow.knowledge.repository;

import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Linha só de leitura de uma fonte curada, com o id da Q&amp;A a que pertence e os dados governados
 * de actualidade (C8, ADR-008) — a actualidade é calculada a partir deles, nunca guardada.
 */
public record KnowledgeSourceReferenceRow(
        UUID questionAnswerId,
        UUID id,
        KnowledgeSourceType sourceType,
        String title,
        String legalReference,
        String url,
        OffsetDateTime createdAt,
        LocalDate validTo,
        OffsetDateTime verifiedAt,
        UUID verifiedBy,
        boolean consolidatedVersionConfirmed,
        boolean stableMatter,
        String stableMatterReason,
        UUID stableMatterMarkedBy,
        LocalDate validFrom
) {

    /** Fonte sem dados de actualidade governados (sem verificação): calcula-se UNCERTAIN. */
    public KnowledgeSourceReferenceRow(UUID questionAnswerId, UUID id, KnowledgeSourceType sourceType, String title,
            String legalReference, String url, OffsetDateTime createdAt) {
        this(questionAnswerId, id, sourceType, title, legalReference, url, createdAt, null, null, null, false, false,
                null, null, null);
    }
}
