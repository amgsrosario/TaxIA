package com.knowledgeflow.ingestion.atfaq;

import java.util.List;
import java.util.Map;

/**
 * Final report of a discovery / dry-run / import run.
 * Serialized to JSON on the {@link AtFaqIngestionRun} record.
 */
public record AtFaqImportReport(
        String mode,
        boolean dryRun,
        int pagesDiscovered,
        int pagesFetched,
        int pagesNotModified,
        int faqsFound,
        int faqsParsed,
        int parseFailures,
        int newItems,
        int unchangedItems,
        int changedItems,
        int possiblyRemoved,
        int duplicates,
        int importedToQuarantine,
        int legalReferencesFound,
        int rejectedUrls,
        long durationMillis,
        int httpRequests,
        int httpRetries,
        Map<String, Integer> httpStatusCodes,
        boolean truncatedByLimit,
        List<String> issues) {
}
