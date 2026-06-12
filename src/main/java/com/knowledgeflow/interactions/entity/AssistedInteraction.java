package com.knowledgeflow.interactions.entity;

import com.knowledgeflow.clients.entity.Client;
import com.knowledgeflow.interactions.enums.AssistedInteractionStatus;
import com.knowledgeflow.organizations.entity.Organization;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "assisted_interactions")
public class AssistedInteraction {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdByUser;

    @Column(nullable = false, length = 254)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AssistedInteractionStatus status;

    @Column(name = "promoted_case_id")
    private UUID promotedCaseId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AssistedInteraction() {}

    public AssistedInteraction(Organization organization, Client client, User createdByUser, String title) {
        this.organization = organization;
        this.client = client;
        this.createdByUser = createdByUser;
        this.title = title;
        this.status = AssistedInteractionStatus.OPEN;
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

    public void promote(UUID caseId) {
        this.promotedCaseId = caseId;
        this.status = AssistedInteractionStatus.PROMOTED;
    }

    public void close() {
        this.status = AssistedInteractionStatus.CLOSED;
    }

    public UUID getId() { return id; }
    public Organization getOrganization() { return organization; }
    public Client getClient() { return client; }
    public User getCreatedByUser() { return createdByUser; }
    public String getTitle() { return title; }
    public AssistedInteractionStatus getStatus() { return status; }
    public UUID getPromotedCaseId() { return promotedCaseId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
