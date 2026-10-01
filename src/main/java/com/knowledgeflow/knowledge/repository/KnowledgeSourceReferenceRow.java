package com.knowledgeflow.knowledge.repository;

import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Linha só de leitura de uma fonte curada, com o id da Q&amp;A a que pertence. */
public record KnowledgeSourceReferenceRow(
        UUID questionAnswerId,
        UUID id,
        KnowledgeSourceType sourceType,
        String title,
        String legalReference,
        String url,
        OffsetDateTime createdAt
) {}
