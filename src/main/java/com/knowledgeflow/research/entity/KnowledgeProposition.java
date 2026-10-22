package com.knowledgeflow.research.entity;

import com.knowledgeflow.research.enums.ConditionKind;
import com.knowledgeflow.research.enums.PropositionConfidence;
import com.knowledgeflow.research.enums.PropositionKind;
import com.knowledgeflow.research.enums.PropositionStatus;
import com.knowledgeflow.research.enums.ValidityStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Unidade de conhecimento estruturado independente da formulação Q&amp;A (B3, KF-03 / CA-04):
 * regra ou proposição com fundamento, condições, excepções, âmbito, aplicabilidade, vigência,
 * proveniência (âncoras literais na evidência), confiança e estado.
 * <p>
 * Não existe estado promovido: {@code CANDIDATE} é conhecimento de investigação utilizável apenas
 * na resposta da própria investigação, sempre com limitações (Aditamento §2). A promoção a
 * conhecimento institucional (B4) está bloqueada até alteração normativa de AGENTS.md e ADR-005.
 */
@Entity
@Table(name = "knowledge_propositions")
public class KnowledgeProposition {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "investigation_id", nullable = false)
    private UUID investigationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PropositionKind kind;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String statement;

    /** Fundamento: referência legal da evidência que sustenta a proposição. */
    @Column(nullable = false, length = 500)
    private String foundation;

    @Column(columnDefinition = "TEXT")
    private String scope;

    @Column(columnDefinition = "TEXT")
    private String applicability;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "validity_status", nullable = false, length = 30)
    private ValidityStatus validityStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PropositionConfidence confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PropositionStatus status;

    @Column(name = "status_reason", nullable = false, columnDefinition = "TEXT")
    private String statusReason;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "knowledge_proposition_supports", joinColumns = @JoinColumn(name = "proposition_id"))
    private List<Support> supports = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "knowledge_proposition_conditions", joinColumns = @JoinColumn(name = "proposition_id"))
    @OrderColumn(name = "position")
    private List<Condition> conditions = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private Integer version;

    /** Âncora literal da proposição numa evidência (fundamento e proveniência). */
    @Embeddable
    public static class Support {
        @Column(name = "evidence_id", nullable = false)
        private UUID evidenceId;

        @Column(name = "anchor_text", nullable = false, columnDefinition = "TEXT")
        private String anchorText;

        @Column(nullable = false)
        private boolean anchored;

        protected Support() {
        }

        public Support(UUID evidenceId, String anchorText, boolean anchored) {
            this.evidenceId = evidenceId;
            this.anchorText = anchorText;
            this.anchored = anchored;
        }

        public UUID getEvidenceId() { return evidenceId; }
        public String getAnchorText() { return anchorText; }
        public boolean isAnchored() { return anchored; }
    }

    /** Condição de aplicação ou excepção, ancorada literalmente na evidência. */
    @Embeddable
    public static class Condition {
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private ConditionKind kind;

        @Column(nullable = false, columnDefinition = "TEXT")
        private String description;

        @Column(name = "anchor_text", nullable = false, columnDefinition = "TEXT")
        private String anchorText;

        @Column(nullable = false)
        private boolean anchored;

        protected Condition() {
        }

        public Condition(ConditionKind kind, String description, String anchorText, boolean anchored) {
            this.kind = kind;
            this.description = description;
            this.anchorText = anchorText;
            this.anchored = anchored;
        }

        public ConditionKind getKind() { return kind; }
        public String getDescription() { return description; }
        public String getAnchorText() { return anchorText; }
        public boolean isAnchored() { return anchored; }
    }

    protected KnowledgeProposition() {
    }

    public KnowledgeProposition(UUID organizationId, UUID investigationId, PropositionKind kind, String statement,
            String foundation, String scope, String applicability, ValidityStatus validityStatus,
            PropositionConfidence confidence, PropositionStatus status, String statusReason,
            List<Support> supports, List<Condition> conditions, OffsetDateTime now) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.investigationId = investigationId;
        this.kind = kind;
        this.statement = statement;
        this.foundation = foundation;
        this.scope = scope;
        this.applicability = applicability;
        this.validityStatus = validityStatus;
        this.confidence = confidence;
        this.status = status;
        this.statusReason = statusReason;
        this.supports = new ArrayList<>(supports);
        this.conditions = new ArrayList<>(conditions);
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Quarentena: proposição envolvida num conflito não resolvido deixa de poder fundamentar. */
    public void quarantine(String reason, OffsetDateTime now) {
        if (status == PropositionStatus.REJECTED) return;
        this.status = PropositionStatus.QUARANTINED;
        this.statusReason = reason;
        this.updatedAt = now;
    }

    public boolean isUsableAsFoundation() {
        return status == PropositionStatus.CANDIDATE && confidence == PropositionConfidence.ANCHORED;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getInvestigationId() { return investigationId; }
    public PropositionKind getKind() { return kind; }
    public String getStatement() { return statement; }
    public String getFoundation() { return foundation; }
    public String getScope() { return scope; }
    public String getApplicability() { return applicability; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }
    public ValidityStatus getValidityStatus() { return validityStatus; }
    public PropositionConfidence getConfidence() { return confidence; }
    public PropositionStatus getStatus() { return status; }
    public String getStatusReason() { return statusReason; }
    public List<Support> getSupports() { return List.copyOf(supports); }
    public List<Condition> getConditions() { return List.copyOf(conditions); }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
