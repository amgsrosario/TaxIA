package com.knowledgeflow.research.entity;

import com.knowledgeflow.research.enums.CriticalClassification;
import com.knowledgeflow.research.enums.CriticalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Entrada da memória crítica (B1, KF-01 / CA-03): afirmação errada, contraditória, insuficiente,
 * rejeitada ou superada, com classificação, razão, proveniência, data/versão, relação com o que a
 * contradiz, estado e histórico ({@link CriticalMemoryHistory}).
 * <p>
 * Nunca é fundamento positivo, em nenhum estado: a resposta só é construída a partir de evidência
 * ACCEPTED e de proposições CANDIDATE ancoradas. Permanece recuperável por {@code referenceKey} para
 * investigação futura, comparação, detecção de recorrência ({@code occurrences}) e reavaliação.
 * A afirmação e a proveniência nunca são alteradas.
 */
@Entity
@Table(name = "critical_memory_entries")
public class CriticalMemoryEntry {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "first_investigation_id")
    private UUID firstInvestigationId;

    @Column(name = "evidence_id")
    private UUID evidenceId;

    @Column(name = "proposition_id")
    private UUID propositionId;

    @Column(name = "reference_key", length = 120)
    private String referenceKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String claim;

    @Column(name = "claim_sha256", nullable = false, length = 64)
    private String claimSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CriticalClassification classification;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String provenance;

    @Column(name = "source_url", length = 2000)
    private String sourceUrl;

    @Column(name = "source_version", length = 255)
    private String sourceVersion;

    @Column(name = "observed_at", nullable = false)
    private OffsetDateTime observedAt;

    @Column(name = "contradicted_by_evidence_id")
    private UUID contradictedByEvidenceId;

    @Column(name = "contradicted_by_proposition_id")
    private UUID contradictedByPropositionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CriticalStatus status;

    @Column(nullable = false)
    private int occurrences;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private Integer version;

    protected CriticalMemoryEntry() {
    }

    public CriticalMemoryEntry(UUID organizationId, UUID firstInvestigationId, UUID evidenceId, UUID propositionId,
            String referenceKey, String claim, String claimSha256, CriticalClassification classification,
            String reason, String provenance, String sourceUrl, String sourceVersion,
            UUID contradictedByEvidenceId, UUID contradictedByPropositionId, OffsetDateTime now) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.firstInvestigationId = firstInvestigationId;
        this.evidenceId = evidenceId;
        this.propositionId = propositionId;
        this.referenceKey = referenceKey;
        this.claim = claim;
        this.claimSha256 = claimSha256;
        this.classification = classification;
        this.reason = reason;
        this.provenance = provenance;
        this.sourceUrl = sourceUrl;
        this.sourceVersion = sourceVersion;
        this.observedAt = now;
        this.contradictedByEvidenceId = contradictedByEvidenceId;
        this.contradictedByPropositionId = contradictedByPropositionId;
        this.status = CriticalStatus.ACTIVE;
        this.occurrences = 1;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void recordRecurrence(OffsetDateTime now) {
        this.occurrences++;
        this.updatedAt = now;
    }

    public void changeStatus(CriticalStatus newStatus, OffsetDateTime now) {
        this.status = newStatus;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getFirstInvestigationId() { return firstInvestigationId; }
    public UUID getEvidenceId() { return evidenceId; }
    public UUID getPropositionId() { return propositionId; }
    public String getReferenceKey() { return referenceKey; }
    public String getClaim() { return claim; }
    public String getClaimSha256() { return claimSha256; }
    public CriticalClassification getClassification() { return classification; }
    public String getReason() { return reason; }
    public String getProvenance() { return provenance; }
    public String getSourceUrl() { return sourceUrl; }
    public String getSourceVersion() { return sourceVersion; }
    public OffsetDateTime getObservedAt() { return observedAt; }
    public UUID getContradictedByEvidenceId() { return contradictedByEvidenceId; }
    public UUID getContradictedByPropositionId() { return contradictedByPropositionId; }
    public CriticalStatus getStatus() { return status; }
    public int getOccurrences() { return occurrences; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
