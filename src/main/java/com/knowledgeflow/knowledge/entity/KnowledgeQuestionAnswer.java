package com.knowledgeflow.knowledge.entity;

import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.organizations.entity.Organization;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "knowledge_question_answers")
public class KnowledgeQuestionAnswer {

    /** Hard limit of the external_key column. */
    public static final int EXTERNAL_KEY_MAX_LENGTH = 255;

    /**
     * Maximum externalKey length accepted at import time. Reserves room for the
     * versioning suffix ("_v" + version) appended by createNewVersion, so a new
     * version can never overflow the column.
     */
    public static final int EXTERNAL_KEY_MAX_IMPORT_LENGTH = 240;

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    /** Identifier from the source system — used for idempotent re-imports. */
    @Column(length = 255)
    private String externalKey;

    /** Identifies the import batch or origin system (e.g., "csv-import-2026-06-25"). */
    @Column(length = 120)
    private String sourceSystem;

    // --- Immutable originals (never overwritten after first import) -----------

    @Column(nullable = false, columnDefinition = "TEXT")
    private String originalQuestion;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String originalAnswer;

    // --- Curated content (may evolve, each material change creates a version) -

    @Column(columnDefinition = "TEXT")
    private String normalizedQuestion;

    @Column(columnDefinition = "TEXT")
    private String shortAnswer;

    @Column(columnDefinition = "TEXT")
    private String technicalAnswer;

    // --- Classification -------------------------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private KnowledgeTopic topic;

    @Column(length = 120)
    private String subtopic;

    @Column(length = 10)
    private String jurisdiction = "PT";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KnowledgeRiskLevel riskLevel = KnowledgeRiskLevel.MEDIUM;

    @Column(nullable = false)
    private boolean requiresHumanValidation = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KnowledgeCurationStatus curationStatus = KnowledgeCurationStatus.IMPORTED;

    /** When true this entry is the canonical answer for its topic/question cluster. */
    @Column(nullable = false)
    private boolean canonical = false;

    // --- Validity window -----------------------------------------------------

    private LocalDate validFrom;
    private LocalDate validTo;

    // --- Review metadata -----------------------------------------------------

    private OffsetDateTime reviewedAt;

    @Column(length = 255)
    private String reviewedBy;

    @Column(columnDefinition = "TEXT")
    private String notes;

    // --- Publication ---------------------------------------------------------

    private OffsetDateTime publishedAt;

    @Column(length = 255)
    private String publishedBy;

    /** UUID of the previous KnowledgeQuestionAnswer version (no FK to allow deletion). */
    private UUID previousVersionId;

    // --- Audit ---------------------------------------------------------------

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private int version = 0;

    protected KnowledgeQuestionAnswer() {
    }

    public KnowledgeQuestionAnswer(
            Organization organization,
            String originalQuestion,
            String originalAnswer,
            String sourceSystem,
            String externalKey) {
        this.organization = organization;
        this.originalQuestion = originalQuestion;
        this.originalAnswer = originalAnswer;
        this.sourceSystem = sourceSystem;
        this.externalKey = externalKey;
        this.curationStatus = KnowledgeCurationStatus.IMPORTED;
    }

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // -------------------------------------------------------------------------
    // Curation state transitions
    // -------------------------------------------------------------------------

    public void markPendingReview() {
        requireOneOf(KnowledgeCurationStatus.IMPORTED, KnowledgeCurationStatus.NEEDS_UPDATE);
        this.curationStatus = KnowledgeCurationStatus.PENDING_REVIEW;
    }

    public void validate(String reviewedBy) {
        requireOneOf(KnowledgeCurationStatus.PENDING_REVIEW);
        requireNonBlank(reviewedBy, "reviewedBy is required to validate");
        requireValidatedAnswer();
        requireAtLeastOneSource();
        this.curationStatus = KnowledgeCurationStatus.VALIDATED;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = OffsetDateTime.now();
    }

    public void reject(String reviewedBy, String reason) {
        requireOneOf(KnowledgeCurationStatus.PENDING_REVIEW);
        this.curationStatus = KnowledgeCurationStatus.REJECTED;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = OffsetDateTime.now();
        if (reason != null && !reason.isBlank()) {
            this.notes = (notes == null ? "" : notes + "\n") + "Rejeitado: " + reason;
        }
    }

    public void markOutdated() {
        requireOneOf(KnowledgeCurationStatus.VALIDATED, KnowledgeCurationStatus.NEEDS_UPDATE);
        this.curationStatus = KnowledgeCurationStatus.OUTDATED;
        if (this.canonical) this.canonical = false;
    }

    public void markNeedsUpdate() {
        requireOneOf(KnowledgeCurationStatus.VALIDATED, KnowledgeCurationStatus.PENDING_REVIEW);
        this.curationStatus = KnowledgeCurationStatus.NEEDS_UPDATE;
        if (this.canonical) this.canonical = false;
    }

    public void archive() {
        requireOneOf(
                KnowledgeCurationStatus.REJECTED,
                KnowledgeCurationStatus.OUTDATED,
                KnowledgeCurationStatus.NEEDS_UPDATE);
        this.curationStatus = KnowledgeCurationStatus.ARCHIVED;
        if (this.canonical) this.canonical = false;
    }

    public void markPublished(String publishedBy) {
        this.publishedAt = OffsetDateTime.now();
        this.publishedBy = publishedBy;
    }

    public void markUnpublished() {
        this.publishedAt = null;
        this.publishedBy = null;
    }

    // -------------------------------------------------------------------------
    // Canonical management
    // -------------------------------------------------------------------------

    public void setCanonical(boolean canonical) {
        if (canonical && this.curationStatus != KnowledgeCurationStatus.VALIDATED) {
            throw new BusinessException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "Only VALIDATED entries can be marked canonical");
        }
        this.canonical = canonical;
    }

    // -------------------------------------------------------------------------
    // Curation updates (curated fields only — originals are immutable)
    // -------------------------------------------------------------------------

    public void updateCuration(
            String normalizedQuestion,
            String shortAnswer,
            String technicalAnswer,
            KnowledgeTopic topic,
            String subtopic,
            String jurisdiction,
            KnowledgeRiskLevel riskLevel,
            boolean requiresHumanValidation,
            LocalDate validFrom,
            LocalDate validTo,
            String notes) {
        this.normalizedQuestion = normalizedQuestion;
        this.shortAnswer = shortAnswer;
        this.technicalAnswer = technicalAnswer;
        this.topic = topic;
        this.subtopic = subtopic;
        this.jurisdiction = jurisdiction;
        this.riskLevel = riskLevel;
        this.requiresHumanValidation = requiresHumanValidation;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.notes = notes;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    public boolean isEligibleForRag() {
        if (curationStatus != KnowledgeCurationStatus.VALIDATED) return false;
        if (originalQuestion == null || originalQuestion.isBlank()) return false;
        // Regra editorial: casos fiscais/jurídicos só entram no RAG com resposta
        // técnica completa — a shortAnswer sozinha nunca sustenta publicação.
        if (technicalAnswer == null || technicalAnswer.isBlank()) return false;
        if (validTo != null && validTo.isBefore(LocalDate.now())) return false;
        if ((riskLevel == KnowledgeRiskLevel.HIGH || riskLevel == KnowledgeRiskLevel.CRITICAL)
                && (reviewedBy == null || reviewedBy.isBlank())) return false;
        return true;
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    // -------------------------------------------------------------------------
    // Guards
    // -------------------------------------------------------------------------

    private void requireOneOf(KnowledgeCurationStatus... allowed) {
        for (KnowledgeCurationStatus s : allowed) {
            if (this.curationStatus == s) return;
        }
        throw new BusinessException(ApiErrorCode.INVALID_STATE_TRANSITION,
                "Unexpected status %s for this transition".formatted(curationStatus));
    }

    private void requireNonBlank(String value, String msg) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR, msg);
        }
    }

    private void requireValidatedAnswer() {
        // A shortAnswer curada é obrigatória em qualquer avanço relevante de
        // curadoria; a technicalAnswer só é exigida mais tarde, na publicação.
        if (shortAnswer == null || shortAnswer.isBlank()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "A curated shortAnswer is required before validation");
        }
    }

    private void requireAtLeastOneSource() {
        // Source validation is enforced at the service layer (repository query).
        // The entity itself cannot query its own sources; this is a reminder
        // that the calling service must check source existence.
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public UUID getId() { return id; }
    public Organization getOrganization() { return organization; }
    public String getExternalKey() { return externalKey; }
    public String getSourceSystem() { return sourceSystem; }
    public String getOriginalQuestion() { return originalQuestion; }
    public String getOriginalAnswer() { return originalAnswer; }
    public String getNormalizedQuestion() { return normalizedQuestion; }
    public String getShortAnswer() { return shortAnswer; }
    public String getTechnicalAnswer() { return technicalAnswer; }
    public KnowledgeTopic getTopic() { return topic; }
    public String getSubtopic() { return subtopic; }
    public String getJurisdiction() { return jurisdiction; }
    public KnowledgeRiskLevel getRiskLevel() { return riskLevel; }
    public boolean isRequiresHumanValidation() { return requiresHumanValidation; }
    public KnowledgeCurationStatus getCurationStatus() { return curationStatus; }
    public boolean isCanonical() { return canonical; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }
    public OffsetDateTime getReviewedAt() { return reviewedAt; }
    public String getReviewedBy() { return reviewedBy; }
    public String getNotes() { return notes; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public String getPublishedBy() { return publishedBy; }
    public UUID getPreviousVersionId() { return previousVersionId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public int getVersion() { return version; }

    public void setPreviousVersionId(UUID previousVersionId) {
        this.previousVersionId = previousVersionId;
    }
}
