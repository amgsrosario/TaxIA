package com.knowledgeflow.knowledge.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Parameters for a Q&A import request (multipart form or JSON body).
 * The actual file content is received as a separate multipart part.
 */
public record KnowledgeQaImportRequest(

        /** Identifies the import batch (e.g., "csv-2026-06-25"). Must be unique per organization. */
        @NotBlank String sourceSystem,

        /** Format of the uploaded file. */
        Format format,

        /** When true, validate and report but do not persist anything. */
        boolean dryRun,

        /** Maximum number of rows to process. 0 means no limit. Negative values are rejected. */
        @Min(value = 0, message = "limit must be 0 (no limit) or a positive number") int limit

) {
    public enum Format {
        CSV, JSON
    }

    public KnowledgeQaImportRequest {
        if (format == null) format = Format.CSV;
        if (limit < 0) limit = 0;
    }
}
