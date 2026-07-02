package com.knowledgeflow.knowledge.entity;

import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "knowledge_source_references")
public class KnowledgeSourceReference {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "knowledge_qa_id", nullable = false)
    private KnowledgeQuestionAnswer questionAnswer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private KnowledgeSourceType sourceType;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 255)
    private String legalReference;

    @Column(length = 500)
    private String url;

    /** FK to an existing KnowledgeCase used as document source (optional). */
    private UUID documentId;

    /** Fragment identifier within the referenced document (optional). */
    private UUID fragmentId;

    private LocalDate validFrom;
    private LocalDate validTo;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected KnowledgeSourceReference() {
    }

    public KnowledgeSourceReference(
            KnowledgeQuestionAnswer questionAnswer,
            KnowledgeSourceType sourceType,
            String title) {
        this.questionAnswer = questionAnswer;
        this.sourceType = sourceType;
        this.title = title;
    }

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = OffsetDateTime.now();
    }

    public void update(
            KnowledgeSourceType sourceType,
            String title,
            String legalReference,
            String url,
            UUID documentId,
            UUID fragmentId,
            LocalDate validFrom,
            LocalDate validTo,
            String notes) {
        this.sourceType = sourceType;
        this.title = title;
        this.legalReference = legalReference;
        this.url = url;
        this.documentId = documentId;
        this.fragmentId = fragmentId;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.notes = notes;
    }

    // Getters
    public UUID getId() { return id; }
    public KnowledgeQuestionAnswer getQuestionAnswer() { return questionAnswer; }
    public KnowledgeSourceType getSourceType() { return sourceType; }
    public String getTitle() { return title; }
    public String getLegalReference() { return legalReference; }
    public String getUrl() { return url; }
    public UUID getDocumentId() { return documentId; }
    public UUID getFragmentId() { return fragmentId; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }
    public String getNotes() { return notes; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
