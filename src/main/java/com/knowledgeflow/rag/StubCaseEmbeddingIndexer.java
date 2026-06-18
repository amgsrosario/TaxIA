package com.knowledgeflow.rag;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * No-op indexer used in the "test" profile.
 * Prevents execution of PostgreSQL-specific SQL (?::vector) against H2.
 */
@Component
@Profile("test")
public class StubCaseEmbeddingIndexer implements CaseEmbeddingIndexer {

    private static final Logger log = LoggerFactory.getLogger(StubCaseEmbeddingIndexer.class);

    @Override
    public void indexValidatedCase(UUID knowledgeCaseId, String title, String question, String content) {
        log.debug("StubCaseEmbeddingIndexer: skipping indexing for case {}", knowledgeCaseId);
    }
}
