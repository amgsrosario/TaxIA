package com.knowledgeflow.knowledge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.service.AuditService;
import com.knowledgeflow.knowledge.dto.ImportIssue;
import com.knowledgeflow.knowledge.dto.ImportIssue.IssueType;
import com.knowledgeflow.knowledge.dto.ImportReport;
import com.knowledgeflow.knowledge.dto.ImportRow;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.knowledge.service.KnowledgeDuplicateDetector.DuplicateResult;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports Q&A pairs from CSV or JSON files.
 * <p>
 * Principle: no imported row is treated as valid knowledge just because it exists
 * in the source file. All imported entries start as IMPORTED status.
 * <p>
 * Idempotent per (organizationId, sourceSystem, externalKey).
 */
@Service
public class KnowledgeQuestionAnswerImportService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQuestionAnswerImportService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final KnowledgeQuestionAnswerRepository qaRepository;
    private final KnowledgeSourceReferenceRepository sourceRepository;
    private final OrganizationRepository organizationRepository;
    private final KnowledgeQuestionAnswerNormalizer normalizer;
    private final KnowledgeDuplicateDetector duplicateDetector;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final int maxRows;

    public KnowledgeQuestionAnswerImportService(
            KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeSourceReferenceRepository sourceRepository,
            OrganizationRepository organizationRepository,
            KnowledgeQuestionAnswerNormalizer normalizer,
            KnowledgeDuplicateDetector duplicateDetector,
            AuditService auditService,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${knowledgeflow.knowledge.import.max-rows:1000}") int maxRows) {
        this.qaRepository = qaRepository;
        this.sourceRepository = sourceRepository;
        this.organizationRepository = organizationRepository;
        this.normalizer = normalizer;
        this.duplicateDetector = duplicateDetector;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.maxRows = maxRows > 0 ? maxRows : 1000;
    }

    /** Rejects oversized import files with a controlled error before any processing. */
    private void assertWithinRowLimit(List<ImportRow> rows) {
        if (rows.size() > maxRows) {
            throw new com.knowledgeflow.common.error.BusinessException(
                    com.knowledgeflow.common.error.ApiErrorCode.VALIDATION_ERROR,
                    "Import file has %d rows — the maximum allowed is %d".formatted(rows.size(), maxRows));
        }
    }

    // -------------------------------------------------------------------------
    // CSV import
    // -------------------------------------------------------------------------

    @Transactional
    public ImportReport importCsv(
            UUID organizationId,
            UUID actingUserId,
            String sourceSystem,
            InputStream csvStream,
            boolean dryRun,
            int limit) throws IOException {

        Organization org = loadOrganization(organizationId);
        List<ImportRow> rows = parseCsv(csvStream);
        assertWithinRowLimit(rows);
        return processRows(org, actingUserId, sourceSystem, rows, dryRun, limit);
    }

    // -------------------------------------------------------------------------
    // JSON import
    // -------------------------------------------------------------------------

    @Transactional
    public ImportReport importJson(
            UUID organizationId,
            UUID actingUserId,
            String sourceSystem,
            InputStream jsonStream,
            boolean dryRun,
            int limit) throws IOException {

        Organization org = loadOrganization(organizationId);
        List<ImportRow> rows = parseJson(jsonStream);
        assertWithinRowLimit(rows);
        return processRows(org, actingUserId, sourceSystem, rows, dryRun, limit);
    }

    // -------------------------------------------------------------------------
    // Core processing
    // -------------------------------------------------------------------------

    List<ImportRow> parseCsv(InputStream stream) throws IOException {
        List<ImportRow> rows = new ArrayList<>();
        // Skip UTF-8 BOM if present
        Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true)
                .setTrim(true)
                .setIgnoreEmptyLines(true)
                .build();

        try (CSVParser parser = format.parse(reader)) {
            int rowNum = 1;
            for (CSVRecord record : parser) {
                rows.add(new ImportRow(
                        rowNum++,
                        optional(record, "externalKey", "external_key", "id"),
                        optional(record, "question"),
                        optional(record, "answer"),
                        optional(record, "topic"),
                        optional(record, "subtopic"),
                        optional(record, "jurisdiction"),
                        optional(record, "riskLevel", "risk_level"),
                        optional(record, "requiresHumanValidation", "requires_human_validation"),
                        optional(record, "sourceReference", "source_reference"),
                        optional(record, "validFrom", "valid_from"),
                        optional(record, "validTo", "valid_to"),
                        optional(record, "notes")));
            }
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    List<ImportRow> parseJson(InputStream stream) throws IOException {
        List<Map<String, Object>> raw = objectMapper.readValue(
                stream, new TypeReference<>() {});
        List<ImportRow> rows = new ArrayList<>();
        int rowNum = 1;
        for (Map<String, Object> m : raw) {
            rows.add(new ImportRow(
                    rowNum++,
                    str(m, "externalKey"),
                    str(m, "question"),
                    str(m, "answer"),
                    str(m, "topic"),
                    str(m, "subtopic"),
                    str(m, "jurisdiction"),
                    str(m, "riskLevel"),
                    str(m, "requiresHumanValidation"),
                    str(m, "sourceReference"),
                    str(m, "validFrom"),
                    str(m, "validTo"),
                    str(m, "notes")));
        }
        return rows;
    }

    private ImportReport processRows(
            Organization org,
            UUID actingUserId,
            String sourceSystem,
            List<ImportRow> rows,
            boolean dryRun,
            int limit) {

        int totalRows = rows.size();
        int imported = 0, updated = 0, skipped = 0, duplicated = 0, invalid = 0, warnings = 0;
        List<ImportIssue> issues = new ArrayList<>();

        for (ImportRow row : rows) {
            if (limit > 0 && (imported + updated) >= limit) {
                issues.add(new ImportIssue(row.rowNumber(), row.externalKey(),
                        IssueType.DRY_RUN_SKIPPED, "Limit of %d rows reached".formatted(limit)));
                skipped++;
                continue;
            }

            // Validate mandatory fields
            if (row.question() == null || row.question().isBlank()) {
                issues.add(new ImportIssue(row.rowNumber(), row.externalKey(),
                        IssueType.INVALID_ROW, "Field 'question' is required"));
                invalid++;
                continue;
            }
            if (row.answer() == null || row.answer().isBlank()) {
                issues.add(new ImportIssue(row.rowNumber(), row.externalKey(),
                        IssueType.INVALID_ROW, "Field 'answer' is required"));
                invalid++;
                continue;
            }
            if (row.externalKey() != null
                    && row.externalKey().length() > KnowledgeQuestionAnswer.EXTERNAL_KEY_MAX_IMPORT_LENGTH) {
                issues.add(new ImportIssue(row.rowNumber(), row.externalKey(),
                        IssueType.INVALID_ROW,
                        "Field 'externalKey' exceeds %d characters (space is reserved for the versioning suffix)"
                                .formatted(KnowledgeQuestionAnswer.EXTERNAL_KEY_MAX_IMPORT_LENGTH)));
                invalid++;
                continue;
            }

            // Non-blocking warnings: silent fallbacks and mandatory-review flags,
            // surfaced so the curator sees them in dry-run BEFORE importing.
            warnings += collectRowWarnings(row, issues);

            // Duplicate check
            var dupCheck = duplicateDetector.check(org.getId(), sourceSystem, row);

            if (dupCheck.result() == DuplicateResult.SAME_EXTERNAL_KEY) {
                // Idempotent: same key → update metadata only (not originals)
                var issue = duplicateDetector.toImportIssue(row.rowNumber(), row.externalKey(), dupCheck);
                if (issue != null) issues.add(issue);
                if (!dryRun) {
                    updateMetadata(dupCheck.existing().get(), row);
                    updated++;
                } else {
                    issues.add(new ImportIssue(row.rowNumber(), row.externalKey(),
                            IssueType.DRY_RUN_SKIPPED,
                            "Dry-run: would UPDATE existing entry (same sourceSystem+externalKey; originals preserved)"));
                    skipped++;
                }
                continue;
            }

            if (dupCheck.result() == DuplicateResult.EXACT_DUPLICATE) {
                var issue = duplicateDetector.toImportIssue(row.rowNumber(), row.externalKey(), dupCheck);
                if (issue != null) issues.add(issue);
                duplicated++;
                skipped++;
                continue;
            }

            if (dupCheck.result() == DuplicateResult.SAME_QUESTION_DIFFERENT_ANSWER) {
                // Signal conflict, but still import (curator will review)
                var issue = duplicateDetector.toImportIssue(row.rowNumber(), row.externalKey(), dupCheck);
                if (issue != null) issues.add(issue);
                // Fall through to import
            }

            if (dryRun) {
                if (dupCheck.result() == DuplicateResult.NONE
                        || dupCheck.result() == DuplicateResult.SAME_QUESTION_DIFFERENT_ANSWER) {
                    issues.add(new ImportIssue(row.rowNumber(), row.externalKey(),
                            IssueType.DRY_RUN_SKIPPED,
                            "Dry-run: would CREATE new entry (status IMPORTED)"));
                    skipped++;
                }
                continue;
            }

            KnowledgeQuestionAnswer qa = createEntry(org, sourceSystem, row);
            qaRepository.save(qa);

            if (row.sourceReference() != null && !row.sourceReference().isBlank()) {
                sourceRepository.save(new KnowledgeSourceReference(
                        qa, KnowledgeSourceType.INTERNAL_OPINION, row.sourceReference()));
            }

            auditService.record(org.getId(), actingUserId,
                    AuditAction.KNOWLEDGE_QA_IMPORTED,
                    "KnowledgeQuestionAnswer", qa.getId(),
                    "sourceSystem=%s externalKey=%s".formatted(sourceSystem, row.externalKey()));

            imported++;
            log.debug("Imported QA row={} externalKey={} id={}", row.rowNumber(), row.externalKey(), qa.getId());
        }

        return new ImportReport(totalRows, imported, updated, skipped, duplicated, invalid, warnings, dryRun, issues);
    }

    /**
     * Non-blocking per-row warnings for the curator. Detects silent fallbacks
     * (invalid dates ignored, unknown riskLevel → MEDIUM, unknown topic → OUTROS)
     * and flags rows whose risk classification makes human review mandatory.
     * Returns the number of warnings added.
     */
    private int collectRowWarnings(ImportRow row, List<ImportIssue> issues) {
        int count = 0;
        count += warnIf(row.validFrom() != null && !row.validFrom().isBlank()
                        && parseDate(row.validFrom()) == null,
                row, issues, "Campo 'validFrom' com formato inválido (esperado yyyy-MM-dd) — será ignorado");
        count += warnIf(row.validTo() != null && !row.validTo().isBlank()
                        && parseDate(row.validTo()) == null,
                row, issues, "Campo 'validTo' com formato inválido (esperado yyyy-MM-dd) — será ignorado");
        count += warnIf(row.riskLevel() != null && !row.riskLevel().isBlank()
                        && !isKnownRiskLevel(row.riskLevel()),
                row, issues, "riskLevel desconhecido '%s' — será classificado como MEDIUM".formatted(row.riskLevel()));
        count += warnIf(row.topic() != null && !row.topic().isBlank()
                        && !isKnownTopic(row.topic()),
                row, issues, "topic desconhecido '%s' — será classificado como OUTROS".formatted(row.topic()));

        KnowledgeRiskLevel risk = parseRiskLevel(row.riskLevel());
        boolean highRisk = risk == KnowledgeRiskLevel.HIGH || risk == KnowledgeRiskLevel.CRITICAL;
        count += warnIf(highRisk,
                row, issues, "Risco %s: revisão humana obrigatória antes da validação".formatted(risk));
        count += warnIf(highRisk && !parseBoolean(row.requiresHumanValidation()),
                row, issues, "requiresHumanValidation deve ser true para risco HIGH/CRITICAL — corrigir na curadoria antes de validar");
        return count;
    }

    private int warnIf(boolean condition, ImportRow row, List<ImportIssue> issues, String message) {
        if (!condition) return 0;
        issues.add(new ImportIssue(row.rowNumber(), row.externalKey(), IssueType.WARNING, message));
        return 1;
    }

    private boolean isKnownRiskLevel(String raw) {
        try {
            KnowledgeRiskLevel.valueOf(raw.trim().toUpperCase());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isKnownTopic(String raw) {
        try {
            KnowledgeTopic.valueOf(raw.trim().toUpperCase());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private KnowledgeQuestionAnswer createEntry(Organization org, String sourceSystem, ImportRow row) {
        KnowledgeQuestionAnswer qa = new KnowledgeQuestionAnswer(
                org,
                normalizer.normalizeAnswer(row.question()),
                normalizer.normalizeAnswer(row.answer()),
                sourceSystem,
                row.externalKey());

        qa.updateCuration(
                normalizer.normalizeQuestion(row.question()),
                null,
                null,
                parseTopic(row.topic()),
                row.subtopic(),
                row.jurisdiction() != null && !row.jurisdiction().isBlank() ? row.jurisdiction() : "PT",
                parseRiskLevel(row.riskLevel()),
                parseBoolean(row.requiresHumanValidation()),
                parseDate(row.validFrom()),
                parseDate(row.validTo()),
                row.notes());
        return qa;
    }

    private void updateMetadata(KnowledgeQuestionAnswer qa, ImportRow row) {
        // Only update non-original fields on re-import
        qa.updateCuration(
                normalizer.normalizeQuestion(row.question()),
                qa.getShortAnswer(),
                qa.getTechnicalAnswer(),
                row.topic() != null ? parseTopic(row.topic()) : qa.getTopic(),
                row.subtopic() != null ? row.subtopic() : qa.getSubtopic(),
                row.jurisdiction() != null && !row.jurisdiction().isBlank()
                        ? row.jurisdiction() : qa.getJurisdiction(),
                row.riskLevel() != null ? parseRiskLevel(row.riskLevel()) : qa.getRiskLevel(),
                parseBoolean(row.requiresHumanValidation()),
                parseDate(row.validFrom()),
                parseDate(row.validTo()),
                row.notes() != null ? row.notes() : qa.getNotes());
    }

    private Organization loadOrganization(UUID organizationId) {
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Organization not found: " + organizationId));
    }

    private KnowledgeTopic parseTopic(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return KnowledgeTopic.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return KnowledgeTopic.OUTROS;
        }
    }

    private KnowledgeRiskLevel parseRiskLevel(String raw) {
        if (raw == null || raw.isBlank()) return KnowledgeRiskLevel.MEDIUM;
        try {
            return KnowledgeRiskLevel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return KnowledgeRiskLevel.MEDIUM;
        }
    }

    private boolean parseBoolean(String raw) {
        if (raw == null) return false;
        return "true".equalsIgnoreCase(raw.trim()) || "1".equals(raw.trim());
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim(), DATE_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String optional(CSVRecord record, String... names) {
        for (String name : names) {
            try {
                if (record.isMapped(name)) {
                    String v = record.get(name);
                    if (v != null && !v.isBlank()) return v;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }
}
