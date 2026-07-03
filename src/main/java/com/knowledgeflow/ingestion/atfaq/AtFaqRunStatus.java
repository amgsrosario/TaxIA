package com.knowledgeflow.ingestion.atfaq;

/** Outcome of an ingestion run. */
public enum AtFaqRunStatus {
    RUNNING,
    COMPLETED,
    FAILED,

    /** Aborted because the source answered 403/429/503 or a security rule fired. */
    BLOCKED
}
