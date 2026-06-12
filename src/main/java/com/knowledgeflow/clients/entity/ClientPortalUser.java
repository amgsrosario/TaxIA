package com.knowledgeflow.clients.entity;

import com.knowledgeflow.clients.enums.ClientPortalUserStatus;
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
@Table(name = "client_portal_users")
public class ClientPortalUser {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ClientPortalUserStatus status;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    private OffsetDateTime deletedAt;

    protected ClientPortalUser() {
    }

    public ClientPortalUser(Client client, Organization organization, String email, String passwordHash) {
        this.client = client;
        this.organization = organization;
        this.email = email;
        this.passwordHash = passwordHash;
        this.status = ClientPortalUserStatus.ACTIVE;
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

    public void deactivate() {
        this.status = ClientPortalUserStatus.INACTIVE;
        this.deletedAt = OffsetDateTime.now();
    }

    public void suspend() {
        this.status = ClientPortalUserStatus.SUSPENDED;
    }

    public UUID getId() { return id; }
    public Client getClient() { return client; }
    public Organization getOrganization() { return organization; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public ClientPortalUserStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
}
