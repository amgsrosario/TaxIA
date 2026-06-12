package com.knowledgeflow.audit.entity;

import com.knowledgeflow.audit.enums.AuditAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID organizationId;

    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 80)
    private AuditAction action;

    @Column(nullable = false, length = 80)
    private String entityType;

    @Column(nullable = false)
    private UUID entityId;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(nullable = false)
    private OffsetDateTime occurredAt;

    protected AuditEvent() {
    }

    public AuditEvent(
            UUID organizationId,
            UUID userId,
            AuditAction action,
            String entityType,
            UUID entityId,
            String metadata
    ) {
        this.organizationId = organizationId;
        this.userId = userId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.metadata = metadata;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        occurredAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public AuditAction getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public String getMetadata() {
        return metadata;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }
}
