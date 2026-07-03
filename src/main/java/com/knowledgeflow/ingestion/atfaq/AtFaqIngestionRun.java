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

/** One discovery / dry-run / import execution, with its final report. */
@Entity
@Table(name = "at_faq_ingestion_runs")
public class AtFaqIngestionRun {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AtFaqRunMode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AtFaqRunStatus status;

    private UUID triggeredBy;

    @Column(nullable = false)
    private OffsetDateTime startedAt;

    private OffsetDateTime finishedAt;

    @Column(columnDefinition = "TEXT")
    private String reportJson;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private int version = 0;

    protected AtFaqIngestionRun() {
    }

    public AtFaqIngestionRun(Organization organization, AtFaqRunMode mode, UUID triggeredBy) {
        this.organization = organization;
        this.mode = mode;
        this.triggeredBy = triggeredBy;
        this.status = AtFaqRunStatus.RUNNING;
        this.startedAt = OffsetDateTime.now();
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

    public void complete(String reportJson) {
        this.status = AtFaqRunStatus.COMPLETED;
        this.finishedAt = OffsetDateTime.now();
        this.reportJson = reportJson;
    }

    public void fail(String errorMessage, String reportJson) {
        this.status = AtFaqRunStatus.FAILED;
        this.finishedAt = OffsetDateTime.now();
        this.errorMessage = errorMessage;
        this.reportJson = reportJson;
    }

    public void block(String errorMessage, String reportJson) {
        this.status = AtFaqRunStatus.BLOCKED;
        this.finishedAt = OffsetDateTime.now();
        this.errorMessage = errorMessage;
        this.reportJson = reportJson;
    }

    public UUID getId() { return id; }
    public Organization getOrganization() { return organization; }
    public AtFaqRunMode getMode() { return mode; }
    public AtFaqRunStatus getStatus() { return status; }
    public UUID getTriggeredBy() { return triggeredBy; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public String getReportJson() { return reportJson; }
    public String getErrorMessage() { return errorMessage; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
