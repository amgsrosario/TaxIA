package com.knowledgeflow.knowledge.rag;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * No-op indexer for the test profile: avoids PostgreSQL-specific pgvector SQL in H2.
 */
@Component
@Profile({"test", "pgtest"})
public class StubKnowledgeQaEmbeddingIndexer implements KnowledgeQaEmbeddingIndexer {

    private static final Logger log = LoggerFactory.getLogger(StubKnowledgeQaEmbeddingIndexer.class);

    @Override
    public void index(UUID qaId, String question, String answer, String topic) {
        log.debug("StubKnowledgeQaEmbeddingIndexer.index called — qaId={}", qaId);
    }

    @Override
    public void remove(UUID qaId) {
        log.debug("StubKnowledgeQaEmbeddingIndexer.remove called — qaId={}", qaId);
    }
}
