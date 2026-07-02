package com.knowledgeflow.knowledge.dto;

import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import java.util.UUID;

public record SimilarQaResult(
        UUID id,
        String originalQuestion,
        KnowledgeCurationStatus curationStatus,
        double similarity,
        boolean potentialConflict
) {
}
