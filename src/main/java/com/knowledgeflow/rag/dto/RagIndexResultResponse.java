package com.knowledgeflow.rag.dto;

import java.util.UUID;

public record RagIndexResultResponse(
        UUID knowledgeCaseId,
        boolean indexed,
        String message
) {}
