package com.knowledgeflow.rag.dto;

public record RagBatchIndexResultResponse(
        int total,
        int indexed,
        int failed
) {}
