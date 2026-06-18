package com.knowledgeflow.rag;

import java.util.List;

/**
 * Contract for producing vector embeddings from text.
 * HttpEmbeddingService is the production implementation; StubEmbeddingService is used in tests.
 */
public interface EmbeddingService {

    List<Float> embedQuery(String text);

    List<Float> embedPassage(String text);
}
