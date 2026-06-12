package com.knowledgeflow.cases.entity;

import com.knowledgeflow.audit.enums.AuditAction;
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
@Table(name = "knowledge_case_comments")
public class KnowledgeCaseComment {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "knowledge_case_id", nullable = false)
    private KnowledgeCase knowledgeCase;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdByUser;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 80)
    private AuditAction workflowAction;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected KnowledgeCaseComment() {
    }

    public KnowledgeCaseComment(KnowledgeCase knowledgeCase, User createdByUser, AuditAction workflowAction, String content) {
        this.knowledgeCase = knowledgeCase;
        this.createdByUser = createdByUser;
        this.workflowAction = workflowAction;
        this.content = content;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public KnowledgeCase getKnowledgeCase() {
        return knowledgeCase;
    }

    public User getCreatedByUser() {
        return createdByUser;
    }

    public AuditAction getWorkflowAction() {
        return workflowAction;
    }

    public String getContent() {
        return content;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
