package com.knowledgeflow.billing.entity;

import com.knowledgeflow.billing.enums.PlanType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "commercial_plans")
public class CommercialPlan {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type", nullable = false, length = 40)
    private PlanType planType;

    /** Maximum number of KnowledgeCases allowed per active period. Null = unlimited. */
    @Column(name = "max_cases")
    private Integer maxCases;

    /** Maximum number of AI interactions per active period. Null = unlimited. */
    @Column(name = "max_interactions")
    private Integer maxInteractions;

    /** Maximum number of client portal users. Null = unlimited. */
    @Column(name = "max_portal_users")
    private Integer maxPortalUsers;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CommercialPlan() {}

    public CommercialPlan(String name, PlanType planType,
                          Integer maxCases, Integer maxInteractions, Integer maxPortalUsers) {
        this.name = name;
        this.planType = planType;
        this.maxCases = maxCases;
        this.maxInteractions = maxInteractions;
        this.maxPortalUsers = maxPortalUsers;
    }

    @PrePersist
    void onCreate() {
        if (this.id == null) this.id = UUID.randomUUID();
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public PlanType getPlanType() { return planType; }
    public Integer getMaxCases() { return maxCases; }
    public Integer getMaxInteractions() { return maxInteractions; }
    public Integer getMaxPortalUsers() { return maxPortalUsers; }
    public boolean isActive() { return active; }
}
