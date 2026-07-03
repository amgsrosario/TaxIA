package com.knowledgeflow.ingestion.atfaq;

/** What an ingestion run is allowed to do. */
public enum AtFaqRunMode {

    /** Discovery only: locate authorized category pages, persist nothing but the run record. */
    DISCOVER,

    /** Full pipeline (fetch, parse, normalize, hash, classify) with zero writes besides the run record. */
    DRY_RUN,

    /** Persists RAW items and creates quarantined Q&A entries (never validated/published automatically). */
    IMPORT
}
