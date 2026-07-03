package com.knowledgeflow.ingestion.atfaq;

/**
 * Lifecycle state of a RAW FAQ item collected from the AT public FAQ pages.
 * RAW items are source evidence — none of these states implies publication.
 */
public enum AtFaqIngestionStatus {

    /** URL known from discovery; content not fetched yet. */
    DISCOVERED,

    /** Page downloaded; not parsed yet. */
    FETCHED,

    /** Question/answer extracted from HTML. */
    PARSED,

    /** Whitespace-stable representation and content hash computed. */
    NORMALIZED,

    /** Passed all checks; eligible for quarantined import. */
    READY_FOR_IMPORT,

    /** A quarantined KnowledgeQuestionAnswer was created (never VALIDATED/PUBLISHED automatically). */
    IMPORTED,

    /** Content present but with anomalies a curator must look at. */
    NEEDS_REVIEW,

    /** Same official FAQ id re-fetched with a different content hash. */
    CHANGED_AT_SOURCE,

    /** Not found on the source in at least two consecutive runs. */
    POSSIBLY_REMOVED,

    /** Fetch/parse failure recorded for this item. */
    FAILED
}
