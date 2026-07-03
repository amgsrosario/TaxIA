package com.knowledgeflow.ingestion.atfaq;

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
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * RAW FAQ item collected from the AT public FAQ pages.
 * <p>
 * This is source evidence, not a canonical answer: it must never feed the RAG
 * directly. Conversion to {@code KnowledgeQuestionAnswer} always lands in
 * quarantine (IMPORTED) and requires human review. Historical versions are
 * preserved: a changed item supersedes the previous row instead of rewriting it.
 */
@Entity
@Table(name = "at_faq_raw_items")
public class AtFaqRawItem {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 40)
    private String officialFaqId;

    @Column(nullable = false, length = 120)
    private String sourceAuthority;

    @Column(nullable = false, length = 120)
    private String category;

    @Column(length = 120)
    private String subcategory;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String questionRaw;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answerRaw;

    @Column(nullable = false, length = 2000)
    private String sourceUrl;

    @Column(length = 500)
    private String sourceTitle;

    @Column(nullable = false)
    private OffsetDateTime fetchedAt;

    @Column(nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(nullable = false, length = 64)
    private String contentHash;

    @Column(nullable = false, length = 20)
    private String parserVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AtFaqIngestionStatus ingestionStatus;

    /** Newline-separated legal references detected conservatively (not legally validated). */
    @Column(columnDefinition = "TEXT")
    private String detectedLegalReferences;

    /** Newline-separated absolute URLs found inside the answer. */
    @Column(columnDefinition = "TEXT")
    private String detectedLinks;

    @Column(nullable = false)
    private boolean sourceRemoved = false;

    @Column(nullable = false)
    private boolean sourceChanged = false;

    /** Consecutive runs in which this item was not found at the source. */
    @Column(nullable = false)
    private int consecutiveMissCount = 0;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /** Previous version of this item (kept forever — no cascade delete). */
    private UUID previousVersionId;

    /** TRUE once a newer version of the same official FAQ id exists. */
    @Column(nullable = false)
    private boolean superseded = false;

    /** The quarantined KnowledgeQuestionAnswer created from this item, if imported. */
    private UUID importedQaId;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private int version = 0;

    protected AtFaqRawItem() {
    }

    public AtFaqRawItem(
            Organization organization,
            String officialFaqId,
            String sourceAuthority,
            String category,
            String subcategory,
            String questionRaw,
            String answerRaw,
            String sourceUrl,
            String sourceTitle,
            String contentHash,
            String parserVersion,
            OffsetDateTime fetchedAt) {
        this.organization = organization;
        this.officialFaqId = officialFaqId;
        this.sourceAuthority = sourceAuthority;
        this.category = category;
        this.subcategory = subcategory;
        this.questionRaw = questionRaw;
        this.answerRaw = answerRaw;
        this.sourceUrl = sourceUrl;
        this.sourceTitle = sourceTitle;
        this.contentHash = contentHash;
        this.parserVersion = parserVersion;
        this.fetchedAt = fetchedAt;
        this.lastSeenAt = fetchedAt;
        this.ingestionStatus = AtFaqIngestionStatus.NORMALIZED;
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
    // State transitions
    // -------------------------------------------------------------------------

    public void markReadyForImport() {
        this.ingestionStatus = AtFaqIngestionStatus.READY_FOR_IMPORT;
    }

    public void markImported(UUID knowledgeQaId) {
        this.ingestionStatus = AtFaqIngestionStatus.IMPORTED;
        this.importedQaId = knowledgeQaId;
    }

    public void markNeedsReview(String reason) {
        this.ingestionStatus = AtFaqIngestionStatus.NEEDS_REVIEW;
        this.errorMessage = reason;
    }

    public void markChangedAtSource() {
        this.ingestionStatus = AtFaqIngestionStatus.CHANGED_AT_SOURCE;
        this.sourceChanged = true;
    }

    public void markSeen(OffsetDateTime when) {
        this.lastSeenAt = when;
        this.consecutiveMissCount = 0;
        if (this.sourceRemoved) {
            // Item reappeared at the source: clear the removal suspicion.
            this.sourceRemoved = false;
        }
    }

    /**
     * Registers one run in which the item was not found. Only after at least
     * two consecutive misses is the item flagged POSSIBLY_REMOVED — a single
     * absence is never enough to suspect removal.
     */
    public void registerMiss() {
        this.consecutiveMissCount++;
        if (this.consecutiveMissCount >= 2) {
            this.sourceRemoved = true;
            this.ingestionStatus = AtFaqIngestionStatus.POSSIBLY_REMOVED;
        }
    }

    public void supersede() {
        this.superseded = true;
    }

    public void setPreviousVersionId(UUID previousVersionId) {
        this.previousVersionId = previousVersionId;
    }

    public void setDetectedLegalReferences(String detectedLegalReferences) {
        this.detectedLegalReferences = detectedLegalReferences;
    }

    public void setDetectedLinks(String detectedLinks) {
        this.detectedLinks = detectedLinks;
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public UUID getId() { return id; }
    public Organization getOrganization() { return organization; }
    public String getOfficialFaqId() { return officialFaqId; }
    public String getSourceAuthority() { return sourceAuthority; }
    public String getCategory() { return category; }
    public String getSubcategory() { return subcategory; }
    public String getQuestionRaw() { return questionRaw; }
    public String getAnswerRaw() { return answerRaw; }
    public String getSourceUrl() { return sourceUrl; }
    public String getSourceTitle() { return sourceTitle; }
    public OffsetDateTime getFetchedAt() { return fetchedAt; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public String getContentHash() { return contentHash; }
    public String getParserVersion() { return parserVersion; }
    public AtFaqIngestionStatus getIngestionStatus() { return ingestionStatus; }
    public String getDetectedLegalReferences() { return detectedLegalReferences; }
    public String getDetectedLinks() { return detectedLinks; }
    public boolean isSourceRemoved() { return sourceRemoved; }
    public boolean isSourceChanged() { return sourceChanged; }
    public int getConsecutiveMissCount() { return consecutiveMissCount; }
    public String getErrorMessage() { return errorMessage; }
    public UUID getPreviousVersionId() { return previousVersionId; }
    public boolean isSuperseded() { return superseded; }
    public UUID getImportedQaId() { return importedQaId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public int getVersion() { return version; }
}
