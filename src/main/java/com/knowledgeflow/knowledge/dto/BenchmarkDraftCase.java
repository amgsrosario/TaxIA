package com.knowledgeflow.knowledge.dto;

import java.util.List;
import java.util.UUID;

/** Draft benchmark case generated from a validated Q&A pair. Requires human review before use. */
public record BenchmarkDraftCase(
        String caseId,
        UUID sourceQaId,
        String title,
        String question,
        String context,
        List<String> expectedBehaviours,
        List<String> forbiddenBehaviours,
        boolean requiresHumanValidation,
        String category,
        String difficulty
) {
}
