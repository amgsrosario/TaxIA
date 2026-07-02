package com.knowledgeflow.knowledge.enums;

/**
 * Risk level of a Q&A answer.
 * HIGH and CRITICAL require a named reviewer before the entry can be VALIDATED.
 */
public enum KnowledgeRiskLevel {

    /** Simple, stable rule — no special validation required. */
    LOW,

    /** Depends on conditions; moderate review recommended. */
    MEDIUM,

    /** Interpretation, exceptions, deadlines, deductibility, contingency. */
    HIGH,

    /** Litigation, AT responses, sanctions, material values, irreversible acts. */
    CRITICAL
}
