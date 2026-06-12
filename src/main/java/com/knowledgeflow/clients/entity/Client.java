package com.knowledgeflow.clients.entity;

import com.knowledgeflow.clients.enums.ClientStatus;
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
@Table(name = "clients")
public class Client {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 180)
    private String name;

    @Column(length = 64)
    private String taxIdentifier;

    @Column(length = 254)
    private String contactEmail;

    @Column(length = 64)
    private String phone;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ClientStatus status;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    private OffsetDateTime deletedAt;

    protected Client() {
    }

    public Client(
            Organization organization,
            String name,
            String taxIdentifier,
            String contactEmail,
            String phone,
            String notes
    ) {
        this.organization = organization;
        this.name = name;
        this.taxIdentifier = taxIdentifier;
        this.contactEmail = contactEmail;
        this.phone = phone;
        this.notes = notes;
        this.status = ClientStatus.ACTIVE;
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

    public void update(String name, String taxIdentifier, String contactEmail, String phone, String notes) {
        this.name = name;
        this.taxIdentifier = taxIdentifier;
        this.contactEmail = contactEmail;
        this.phone = phone;
        this.notes = notes;
    }

    public void archive() {
        status = ClientStatus.ARCHIVED;
        deletedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public Organization getOrganization() {
        return organization;
    }

    public String getName() {
        return name;
    }

    public String getTaxIdentifier() {
        return taxIdentifier;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getPhone() {
        return phone;
    }

    public String getNotes() {
        return notes;
    }

    public ClientStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }
}
