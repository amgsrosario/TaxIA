package com.knowledgeflow.billing.entity;

import com.knowledgeflow.billing.enums.ConsumptionEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only record of a billable event. Never updated or deleted.
 */
@Entity
@Table(name = "consumption_events")
public class ConsumptionEvent {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 80)
    private ConsumptionEventType eventType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected ConsumptionEvent() {}

    public ConsumptionEvent(UUID organizationId, ConsumptionEventType eventType, UUID entityId) {
        this.organizationId = organizationId;
        this.eventType = eventType;
        this.entityId = entityId;
    }

    @PrePersist
    void onCreate() {
        if (this.id == null) this.id = UUID.randomUUID();
        this.occurredAt = Instant.now();
    }

    // No setters — append-only
    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public ConsumptionEventType getEventType() { return eventType; }
    public UUID getEntityId() { return entityId; }
    public Instant getOccurredAt() { return occurredAt; }
}
