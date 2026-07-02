package com.knowledgeflow.knowledge.service;

import com.knowledgeflow.knowledge.dto.ImportIssue;
import com.knowledgeflow.knowledge.dto.ImportIssue.IssueType;
import com.knowledgeflow.knowledge.dto.ImportRow;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Detects duplicates and potential conflicts in incoming import rows.
 */
@Component
public class KnowledgeDuplicateDetector {

    private final KnowledgeQuestionAnswerRepository repository;
    private final KnowledgeQuestionAnswerNormalizer normalizer;

    public KnowledgeDuplicateDetector(
            KnowledgeQuestionAnswerRepository repository,
            KnowledgeQuestionAnswerNormalizer normalizer) {
        this.repository = repository;
        this.normalizer = normalizer;
    }

    public enum DuplicateResult {
        NONE,
        EXACT_DUPLICATE,
        SAME_QUESTION_SAME_ANSWER,
        SAME_QUESTION_DIFFERENT_ANSWER,
        SAME_EXTERNAL_KEY
    }

    public static record DuplicateCheck(
            DuplicateResult result,
            Optional<KnowledgeQuestionAnswer> existing,
            String message) {
    }

    /** Check a single import row for duplicates against the persistent store. */
    public DuplicateCheck check(UUID organizationId, String sourceSystem, ImportRow row) {

        // 1. Check idempotency key
        if (sourceSystem != null && row.externalKey() != null && !row.externalKey().isBlank()) {
            Optional<KnowledgeQuestionAnswer> byKey =
                    repository.findByOrganizationIdAndSourceSystemAndExternalKey(
                            organizationId, sourceSystem, row.externalKey());
            if (byKey.isPresent()) {
                return new DuplicateCheck(
                        DuplicateResult.SAME_EXTERNAL_KEY,
                        byKey,
                        "externalKey '%s' already exists for sourceSystem '%s'"
                                .formatted(row.externalKey(), sourceSystem));
            }
        }

        // 2. Exact duplicate (same question AND same answer)
        String normalizedQ = normalizer.toComparisonKey(row.question());
        List<KnowledgeQuestionAnswer> byQuestion =
                repository.findByOrganizationIdAndOriginalQuestion(organizationId, row.question());

        for (KnowledgeQuestionAnswer existing : byQuestion) {
            String existingNormQ = normalizer.toComparisonKey(existing.getOriginalQuestion());
            if (!existingNormQ.equals(normalizedQ)) continue;

            String existingNormA = normalizer.toComparisonKey(existing.getOriginalAnswer());
            String incomingNormA = normalizer.toComparisonKey(row.answer());

            if (existingNormA.equals(incomingNormA)) {
                return new DuplicateCheck(
                        DuplicateResult.EXACT_DUPLICATE,
                        Optional.of(existing),
                        "Identical question and answer already exist (id=%s)".formatted(existing.getId()));
            } else {
                return new DuplicateCheck(
                        DuplicateResult.SAME_QUESTION_DIFFERENT_ANSWER,
                        Optional.of(existing),
                        "Same question with different answer — potential conflict (id=%s)"
                                .formatted(existing.getId()));
            }
        }

        return new DuplicateCheck(DuplicateResult.NONE, Optional.empty(), null);
    }

    public ImportIssue toImportIssue(int rowNumber, String externalKey, DuplicateCheck check) {
        return switch (check.result()) {
            case EXACT_DUPLICATE -> new ImportIssue(
                    rowNumber, externalKey, IssueType.EXACT_DUPLICATE, check.message());
            case SAME_QUESTION_SAME_ANSWER -> new ImportIssue(
                    rowNumber, externalKey, IssueType.SAME_QUESTION_SAME_ANSWER, check.message());
            case SAME_QUESTION_DIFFERENT_ANSWER -> new ImportIssue(
                    rowNumber, externalKey, IssueType.POTENTIAL_CONFLICT, check.message());
            case SAME_EXTERNAL_KEY -> new ImportIssue(
                    rowNumber, externalKey, IssueType.DUPLICATE_KEY, check.message());
            default -> null;
        };
    }
}
