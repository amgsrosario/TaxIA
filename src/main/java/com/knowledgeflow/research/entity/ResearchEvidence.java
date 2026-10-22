package com.knowledgeflow.research.entity;

import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.research.enums.EvidenceClassification;
import com.knowledgeflow.research.enums.ValidityStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Evidência oficial recolhida numa investigação: EVIDÊNCIA DE INVESTIGAÇÃO NÃO PROMOVIDA
 * (AR2-DEC-KF-2026-10-22, Aditamento §2).
 * <p>
 * Conserva origem (URL, autoridade, referência legal), contexto (investigação e passo de consulta),
 * identificação (SHA-256 do conteúdo) e data de recolha. Não é publicada nem indexada no RAG; a
 * coluna {@code promotion_status} só admite {@code NOT_PROMOTED} (CHECK na base). O conteúdo e a
 * proveniência nunca são alterados; só a classificação pode passar a CONTRADICTED quando um
 * conflito é detectado na mesma investigação.
 */
@Entity
@Table(name = "research_evidence")
public class ResearchEvidence {

    public static final String NOT_PROMOTED = "NOT_PROMOTED";

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "investigation_id", nullable = false)
    private UUID investigationId;

    @Column(name = "consultation_step_id", nullable = false)
    private UUID consultationStepId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 40)
    private KnowledgeSourceType sourceType;

    @Column(nullable = false, length = 60)
    private String authority;

    @Column(name = "reference_key", nullable = false, length = 120)
    private String referenceKey;

    @Column(name = "legal_reference", nullable = false, length = 500)
    private String legalReference;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false, length = 2000)
    private String url;

    @Column(name = "retrieved_at", nullable = false)
    private OffsetDateTime retrievedAt;

    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "amendment_notes", columnDefinition = "TEXT")
    private String amendmentNotes;

    @Enumerated(EnumType.STRING)
    @Column(name = "validity_status", nullable = false, length = 30)
    private ValidityStatus validityStatus;

    @Column(name = "validity_reason", nullable = false, columnDefinition = "TEXT")
    private String validityReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EvidenceClassification classification;

    @Column(name = "classification_reason", nullable = false, columnDefinition = "TEXT")
    private String classificationReason;

    @Column(name = "promotion_status", nullable = false, length = 20)
    private String promotionStatus = NOT_PROMOTED;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ResearchEvidence() {
    }

    public ResearchEvidence(UUID organizationId, UUID investigationId, UUID consultationStepId,
            KnowledgeSourceType sourceType, String authority, String referenceKey, String legalReference,
            String title, String url, OffsetDateTime retrievedAt, String contentSha256, String content,
            String amendmentNotes, ValidityStatus validityStatus, String validityReason,
            EvidenceClassification classification, String classificationReason, OffsetDateTime now) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.investigationId = investigationId;
        this.consultationStepId = consultationStepId;
        this.sourceType = sourceType;
        this.authority = authority;
        this.referenceKey = referenceKey;
        this.legalReference = legalReference;
        this.title = title;
        this.url = url;
        this.retrievedAt = retrievedAt;
        this.contentSha256 = contentSha256;
        this.content = content;
        this.amendmentNotes = amendmentNotes;
        this.validityStatus = validityStatus;
        this.validityReason = validityReason;
        this.classification = classification;
        this.classificationReason = classificationReason;
        this.createdAt = now;
    }

    /** Marca a evidência como contraditada; conteúdo e proveniência mantêm-se. */
    public void markContradicted(String reason) {
        this.classification = EvidenceClassification.CONTRADICTED;
        this.classificationReason = reason;
    }

    public boolean isUsableAsFoundation() {
        return classification == EvidenceClassification.ACCEPTED;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getInvestigationId() { return investigationId; }
    public UUID getConsultationStepId() { return consultationStepId; }
    public KnowledgeSourceType getSourceType() { return sourceType; }
    public String getAuthority() { return authority; }
    public String getReferenceKey() { return referenceKey; }
    public String getLegalReference() { return legalReference; }
    public String getTitle() { return title; }
    public String getUrl() { return url; }
    public OffsetDateTime getRetrievedAt() { return retrievedAt; }
    public String getContentSha256() { return contentSha256; }
    public String getContent() { return content; }
    public String getAmendmentNotes() { return amendmentNotes; }
    public ValidityStatus getValidityStatus() { return validityStatus; }
    public String getValidityReason() { return validityReason; }
    public EvidenceClassification getClassification() { return classification; }
    public String getClassificationReason() { return classificationReason; }
    public String getPromotionStatus() { return promotionStatus; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
