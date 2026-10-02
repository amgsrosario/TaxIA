package com.knowledgeflow.knowledge.repository;

import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import java.util.UUID;

/** Linha só de leitura com os campos de uma Q&amp;A usados para derivar o seu âmbito fiscal. */
public record KnowledgeQaScopeRow(
        UUID id,
        KnowledgeTopic topic,
        String subtopic,
        String originalQuestion,
        String normalizedQuestion
) {}
