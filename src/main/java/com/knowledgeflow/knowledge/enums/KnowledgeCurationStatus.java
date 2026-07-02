package com.knowledgeflow.knowledge.enums;

/**
 * Lifecycle state of an imported Q&A pair.
 * Only VALIDATED entries may feed the RAG index.
 */
public enum KnowledgeCurationStatus {

    /** Received from import, no analysis yet. */
    IMPORTED,

    /** Ready for human review. */
    PENDING_REVIEW,

    /** Approved for use by the RAG. */
    VALIDATED,

    /** Useful but incomplete or potentially outdated — needs rework. */
    NEEDS_UPDATE,

    /** No longer current; must not underpin answers. */
    OUTDATED,

    /** Must not enter the knowledge base. */
    REJECTED,

    /** Retained for historical or audit purposes only. */
    ARCHIVED
}
