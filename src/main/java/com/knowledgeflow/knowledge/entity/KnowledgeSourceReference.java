package com.knowledgeflow.knowledge.entity;

import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "knowledge_source_references")
public class KnowledgeSourceReference {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "knowledge_qa_id", nullable = false)
    private KnowledgeQuestionAnswer questionAnswer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private KnowledgeSourceType sourceType;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(length = 500)
    private String legalReference;

    @Column(length = 2000)
    private String url;

    /** FK to an existing KnowledgeCase used as document source (optional). */
    private UUID documentId;

    /** Fragment identifier within the referenced document (optional). */
    private UUID fragmentId;

    private LocalDate validFrom;
    private LocalDate validTo;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    // --- C8 / ADR-008: verificação humana estruturada (a actualidade é calculada, nunca guardada) ---

    /** Última verificação humana estruturada (relógio das janelas CURRENT/STABLE_BUT_OLD). */
    private OffsetDateTime verifiedAt;

    /** Utilizador (referência governada a {@code users.id}) que fez a verificação. */
    private UUID verifiedBy;

    /** Legislação: confirmada a versão oficial/consolidada aplicável na verificação. */
    @Column(nullable = false)
    private boolean consolidatedVersionConfirmed;

    /** Matéria estável declarada por curador, com justificação (STABLE_BUT_OLD, nunca automático). */
    @Column(nullable = false)
    private boolean stableMatter;

    @Column(columnDefinition = "TEXT")
    private String stableMatterReason;

    private OffsetDateTime stableMatterMarkedAt;

    private UUID stableMatterMarkedBy;

    protected KnowledgeSourceReference() {
    }

    public KnowledgeSourceReference(
            KnowledgeQuestionAnswer questionAnswer,
            KnowledgeSourceType sourceType,
            String title) {
        this.questionAnswer = questionAnswer;
        this.sourceType = sourceType;
        this.title = title;
    }

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = OffsetDateTime.now();
    }

    public void update(
            KnowledgeSourceType sourceType,
            String title,
            String legalReference,
            String url,
            UUID documentId,
            UUID fragmentId,
            LocalDate validFrom,
            LocalDate validTo,
            String notes) {
        this.sourceType = sourceType;
        this.title = title;
        this.legalReference = legalReference;
        this.url = url;
        this.documentId = documentId;
        this.fragmentId = fragmentId;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.notes = notes;
    }

    /** Regista uma verificação humana estruturada (substitui a anterior: o relógio recomeça). */
    public void recordVerification(UUID verifier, OffsetDateTime at, boolean consolidatedConfirmed) {
        if (verifier == null || at == null) {
            throw new IllegalArgumentException("verification requires a verifier and a timestamp");
        }
        this.verifiedBy = verifier;
        this.verifiedAt = at;
        this.consolidatedVersionConfirmed = consolidatedConfirmed;
    }

    /**
     * Revoga a verificação: a fonte volta a não ter prova de actualidade (UNCERTAIN). A matéria
     * estável também é retirada — a decisão tem de ser reexaminada depois de nova verificação.
     */
    public void revokeVerification() {
        this.verifiedBy = null;
        this.verifiedAt = null;
        this.consolidatedVersionConfirmed = false;
        clearStableMatter();
    }

    /** Marca matéria estável: exige justificação não vazia e o curador responsável. */
    public void markStableMatter(UUID curator, OffsetDateTime at, String reason) {
        if (curator == null || at == null || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("stable matter requires a curator, a timestamp and a reason");
        }
        this.stableMatter = true;
        this.stableMatterReason = reason.strip();
        this.stableMatterMarkedAt = at;
        this.stableMatterMarkedBy = curator;
    }

    /** Desmarca matéria estável (conservador); o registo fica na auditoria. */
    public void clearStableMatter() {
        this.stableMatter = false;
        this.stableMatterReason = null;
        this.stableMatterMarkedAt = null;
        this.stableMatterMarkedBy = null;
    }

    // Getters
    public UUID getId() { return id; }
    public KnowledgeQuestionAnswer getQuestionAnswer() { return questionAnswer; }
    public KnowledgeSourceType getSourceType() { return sourceType; }
    public String getTitle() { return title; }
    public String getLegalReference() { return legalReference; }
    public String getUrl() { return url; }
    public UUID getDocumentId() { return documentId; }
    public UUID getFragmentId() { return fragmentId; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }
    public String getNotes() { return notes; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getVerifiedAt() { return verifiedAt; }
    public UUID getVerifiedBy() { return verifiedBy; }
    public boolean isConsolidatedVersionConfirmed() { return consolidatedVersionConfirmed; }
    public boolean isStableMatter() { return stableMatter; }
    public String getStableMatterReason() { return stableMatterReason; }
    public OffsetDateTime getStableMatterMarkedAt() { return stableMatterMarkedAt; }
    public UUID getStableMatterMarkedBy() { return stableMatterMarkedBy; }
}
