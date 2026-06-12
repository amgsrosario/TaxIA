package com.knowledgeflow.cases.entity;

import com.knowledgeflow.cases.enums.KnowledgeCaseStatus;
import com.knowledgeflow.clients.entity.Client;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
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
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "knowledge_cases")
public class KnowledgeCase {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(nullable = false, length = 240)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private KnowledgeCaseStatus status;

    @Column(nullable = false)
    private int currentVersionNumber;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    private OffsetDateTime deletedAt;

    protected KnowledgeCase() {
    }

    public KnowledgeCase(Organization organization, Client client, String title, String question, String content) {
        this.organization = organization;
        this.client = client;
        this.title = title;
        this.question = question;
        this.content = content;
        this.status = KnowledgeCaseStatus.DRAFT;
        this.currentVersionNumber = 0;
    }

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // -------------------------------------------------------------------------
    // Draft editing
    // -------------------------------------------------------------------------

    public void updateDraft(String title, String question, String content) {
        this.title = title;
        this.question = question;
        this.content = content;
    }

    // -------------------------------------------------------------------------
    // Workflow transitions
    // -------------------------------------------------------------------------

    public void submitForReview() {
        requireStatus(KnowledgeCaseStatus.DRAFT);
        this.status = KnowledgeCaseStatus.UNDER_REVIEW;
    }

    public void requestChanges() {
        requireStatus(KnowledgeCaseStatus.UNDER_REVIEW);
        this.status = KnowledgeCaseStatus.CHANGES_REQUESTED;
    }

    public void reopenDraft() {
        requireStatus(KnowledgeCaseStatus.CHANGES_REQUESTED);
        this.status = KnowledgeCaseStatus.DRAFT;
    }

    public void validate() {
        requireStatus(KnowledgeCaseStatus.UNDER_REVIEW);
        this.status = KnowledgeCaseStatus.VALIDATED;
    }

    public void reject() {
        requireStatus(KnowledgeCaseStatus.UNDER_REVIEW);
        this.status = KnowledgeCaseStatus.REJECTED;
    }

    public void archive() {
        if (status != KnowledgeCaseStatus.VALIDATED && status != KnowledgeCaseStatus.REJECTED) {
            throw new BusinessException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "Only validated or rejected cases can be archived");
        }
        this.status = KnowledgeCaseStatus.ARCHIVED;
        this.deletedAt = OffsetDateTime.now();
    }

    // -------------------------------------------------------------------------
    // Versioning
    // -------------------------------------------------------------------------

    public int nextVersionNumber() {
        currentVersionNumber = currentVersionNumber + 1;
        return currentVersionNumber;
    }

    // -------------------------------------------------------------------------
    // Guards
    // -------------------------------------------------------------------------

    private void requireStatus(KnowledgeCaseStatus expected) {
        if (this.status != expected) {
            throw new BusinessException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "Expected status %s but was %s".formatted(expected, this.status));
        }
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public Organization getOrganization() {
        return organization;
    }

    public Client getClient() {
        return client;
    }

    public String getTitle() {
        return title;
    }

    public String getQuestion() {
        return question;
    }

    public String getContent() {
        return content;
    }

    public KnowledgeCaseStatus getStatus() {
        return status;
    }

    public int getCurrentVersionNumber() {
        return currentVersionNumber;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }
}
