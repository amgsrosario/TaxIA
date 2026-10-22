package com.knowledgeflow.research.enums;

/** Resultado de um passo de investigação. */
public enum StepOutcome {
    PROPOSED,
    SKIPPED,
    FETCHED,
    CONFIRMED,
    NOT_CONFIRMED,
    NOT_FOUND,
    BLOCKED,
    FAILED,
    NO_RELEVANT_QA,
    RELEVANT_QA_FOUND
}
