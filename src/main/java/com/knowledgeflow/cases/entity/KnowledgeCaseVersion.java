package com.knowledgeflow.cases.entity;

import com.knowledgeflow.cases.enums.KnowledgeCaseStatus;
import com.knowledgeflow.cases.enums.KnowledgeCaseVersionSourceType;
import com.knowledgeflow.users.entity.User;
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
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "knowledge_case_versions")
public class KnowledgeCaseVersion {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "knowledge_case_id", nullable = false)
    private KnowledgeCase knowledgeCase;

    @Column(nullable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private KnowledgeCaseVersionSourceType sourceType;

    @Column(nullable = false, length = 240)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private KnowledgeCaseStatus statusSnapshot;

    @Column(name = "is_validated_version", nullable = false)
    private boolean validatedVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdByUser;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected KnowledgeCaseVersion() {
    }

    public KnowledgeCaseVersion(
            KnowledgeCase knowledgeCase,
            int versionNumber,
            KnowledgeCaseVersionSourceType sourceType,
            User createdByUser
    ) {
        this.knowledgeCase = knowledgeCase;
        this.versionNumber = versionNumber;
        this.sourceType = sourceType;
        this.title = knowledgeCase.getTitle();
        this.question = knowledgeCase.getQuestion();
        this.content = knowledgeCase.getContent();
        this.statusSnapshot = knowledgeCase.getStatus();
        this.validatedVersion = false;
        this.createdByUser = createdByUser;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = OffsetDateTime.now();
    }

    public void markAsValidated() {
        this.validatedVersion = true;
    }

    public UUID getId() {
        return id;
    }

    public KnowledgeCase getKnowledgeCase() {
        return knowledgeCase;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public KnowledgeCaseVersionSourceType getSourceType() {
        return sourceType;
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

    public KnowledgeCaseStatus getStatusSnapshot() {
        return statusSnapshot;
    }

    public boolean isValidatedVersion() {
        return validatedVersion;
    }

    public User getCreatedByUser() {
        return createdByUser;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
