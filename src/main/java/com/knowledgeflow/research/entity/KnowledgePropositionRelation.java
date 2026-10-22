package com.knowledgeflow.research.entity;

import com.knowledgeflow.research.enums.PropositionRelationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Relação entre duas proposições: conflito, excepção ou refinamento (B3). */
@Entity
@Table(name = "knowledge_proposition_relations")
public class KnowledgePropositionRelation {

    @Id
    private UUID id;

    @Column(name = "from_proposition_id", nullable = false)
    private UUID fromPropositionId;

    @Column(name = "to_proposition_id", nullable = false)
    private UUID toPropositionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PropositionRelationType relation;

    @Column(nullable = false)
    private boolean resolved;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected KnowledgePropositionRelation() {
    }

    public KnowledgePropositionRelation(UUID fromPropositionId, UUID toPropositionId,
            PropositionRelationType relation, String note, OffsetDateTime now) {
        if (fromPropositionId.equals(toPropositionId)) {
            throw new IllegalArgumentException("Uma proposição não se relaciona consigo própria");
        }
        this.id = UUID.randomUUID();
        this.fromPropositionId = fromPropositionId;
        this.toPropositionId = toPropositionId;
        this.relation = relation;
        this.resolved = false;
        this.note = note;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getFromPropositionId() { return fromPropositionId; }
    public UUID getToPropositionId() { return toPropositionId; }
    public PropositionRelationType getRelation() { return relation; }
    public boolean isResolved() { return resolved; }
    public String getNote() { return note; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
