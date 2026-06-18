package com.knowledgeflow.rag;

import java.util.Collections;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Deterministic embedding stub used in the "test" profile.
 * Returns a fixed zero-vector so tests never call the real microservice.
 */
@Component
@Profile("test")
public class StubEmbeddingService implements EmbeddingService {

    private static final int DIMENSION = 384;
    private static final List<Float> ZERO_VECTOR = Collections.nCopies(DIMENSION, 0.0f);

    @Override
    public List<Float> embedQuery(String text) {
        return ZERO_VECTOR;
    }

    @Override
    public List<Float> embedPassage(String text) {
        return ZERO_VECTOR;
    }
}
