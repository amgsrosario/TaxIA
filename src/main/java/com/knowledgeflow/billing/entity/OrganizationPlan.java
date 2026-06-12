package com.knowledgeflow.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organization_plans")
public class OrganizationPlan {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private CommercialPlan plan;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    /** Null means open-ended (active until cancelled) */
    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OrganizationPlan() {}

    public OrganizationPlan(UUID organizationId, CommercialPlan plan, Instant startsAt, Instant endsAt) {
        this.organizationId = organizationId;
        this.plan = plan;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    @PrePersist
    void onCreate() {
        if (this.id == null) this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public CommercialPlan getPlan() { return plan; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
}
