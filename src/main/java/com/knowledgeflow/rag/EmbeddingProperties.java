package com.knowledgeflow.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "knowledgeflow.rag.embeddings")
public record EmbeddingProperties(
        String baseUrl,
        int topK
) {}
