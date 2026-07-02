package com.knowledgeflow.knowledge.dto;

/** A problem detected during import processing of a single row. */
public record ImportIssue(
        int rowNumber,
        String externalKey,
        IssueType type,
        String message
) {
    public enum IssueType {
        /** Mandatory field missing or blank. */
        INVALID_ROW,
        /** externalKey already exists for this sourceSystem; row skipped or updated. */
        DUPLICATE_KEY,
        /** Identical question and answer already present (exact byte match after normalisation). */
        EXACT_DUPLICATE,
        /** Same normalised question, same normalised answer — treated as duplicate but recorded separately. */
        SAME_QUESTION_SAME_ANSWER,
        /** Same question but different answer — possible conflict. */
        POTENTIAL_CONFLICT,
        /** Row parsed but not persisted (dry-run or limit reached). */
        DRY_RUN_SKIPPED,
        /** Non-blocking problem: silent fallback, high risk or mandatory human review. */
        WARNING
    }
}
