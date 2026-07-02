package com.knowledgeflow.knowledge.dto;

import java.util.List;

/** Summary result of a Q&A import operation. */
public record ImportReport(
        int totalRows,
        int imported,
        int updated,
        int skipped,
        int duplicated,
        int invalid,
        int warnings,
        boolean dryRun,
        List<ImportIssue> issues
) {
    public static ImportReport empty(boolean dryRun) {
        return new ImportReport(0, 0, 0, 0, 0, 0, 0, dryRun, List.of());
    }
}
