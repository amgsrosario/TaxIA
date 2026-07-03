package com.knowledgeflow.ingestion.atfaq;

import com.knowledgeflow.organizations.entity.Organization;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Conditional-GET cache for fetched pages: stores ETag / Last-Modified / body
 * hash so unchanged pages are not downloaded and re-processed again.
 */
@Entity
@Table(name = "at_faq_page_snapshots",
        uniqueConstraints = @UniqueConstraint(name = "ux_atfaq_snapshot_org_url",
                columnNames = {"organization_id", "url"}))
public class AtFaqPageSnapshot {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 2000)
    private String url;

    @Column(length = 64)
    private String contentHash;

    @Column(length = 255)
    private String etag;

    @Column(length = 120)
    private String lastModified;

    @Column(nullable = false)
    private OffsetDateTime fetchedAt;

    @Column(nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private int version = 0;

    protected AtFaqPageSnapshot() {
    }

    public AtFaqPageSnapshot(Organization organization, String url) {
        this.organization = organization;
        this.url = url;
        OffsetDateTime now = OffsetDateTime.now();
        this.fetchedAt = now;
        this.lastSeenAt = now;
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

    public void recordFetch(String contentHash, String etag, String lastModified) {
        OffsetDateTime now = OffsetDateTime.now();
        this.contentHash = contentHash;
        this.etag = etag;
        this.lastModified = lastModified;
        this.fetchedAt = now;
        this.lastSeenAt = now;
    }

    public void recordNotModified() {
        this.lastSeenAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public Organization getOrganization() { return organization; }
    public String getUrl() { return url; }
    public String getContentHash() { return contentHash; }
    public String getEtag() { return etag; }
    public String getLastModified() { return lastModified; }
    public OffsetDateTime getFetchedAt() { return fetchedAt; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
}
