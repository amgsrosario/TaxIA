package com.knowledgeflow.research.entity;

import com.knowledgeflow.research.enums.CriticalClassification;
import com.knowledgeflow.research.enums.CriticalHistoryEvent;
import com.knowledgeflow.research.enums.CriticalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Histórico append-only de uma entrada da memória crítica (B1). */
@Entity
@Table(name = "critical_memory_history")
public class CriticalMemoryHistory {

    @Id
    private UUID id;

    @Column(name = "entry_id", nullable = false)
    private UUID entryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CriticalHistoryEvent event;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_classification", length = 30)
    private CriticalClassification fromClassification;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_classification", length = 30)
    private CriticalClassification toClassification;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private CriticalStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 30)
    private CriticalStatus toStatus;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "investigation_id")
    private UUID investigationId;

    @Column(nullable = false, length = 255)
    private String actor;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    protected CriticalMemoryHistory() {
    }

    public CriticalMemoryHistory(UUID entryId, CriticalHistoryEvent event,
            CriticalClassification fromClassification, CriticalClassification toClassification,
            CriticalStatus fromStatus, CriticalStatus toStatus, String reason, UUID investigationId,
            String actor, OffsetDateTime occurredAt) {
        this.id = UUID.randomUUID();
        this.entryId = entryId;
        this.event = event;
        this.fromClassification = fromClassification;
        this.toClassification = toClassification;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.investigationId = investigationId;
        this.actor = actor;
        this.occurredAt = occurredAt;
    }

    public UUID getId() { return id; }
    public UUID getEntryId() { return entryId; }
    public CriticalHistoryEvent getEvent() { return event; }
    public CriticalClassification getFromClassification() { return fromClassification; }
    public CriticalClassification getToClassification() { return toClassification; }
    public CriticalStatus getFromStatus() { return fromStatus; }
    public CriticalStatus getToStatus() { return toStatus; }
    public String getReason() { return reason; }
    public UUID getInvestigationId() { return investigationId; }
    public String getActor() { return actor; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}
