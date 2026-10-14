package com.knowledgeflow.knowledge.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import com.knowledgeflow.knowledge.enums.ApplicabilityRuleKind;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * Regra de aplicabilidade de uma Q&amp;A (M4-SCOPE-V2, ADR-004): exclusão ou, desde o ADR-009, contexto
 * obrigatório ({@link #getKind()}). Enquanto a linha existir, a regra é efectiva no gate — incluindo quando a sua remoção foi pedida e aguarda validação
 * humana ({@link #isRemovalPending()}).
 *
 * <p>O marcador é guardado como código (texto) e não como enum JPA: um código desconhecido na BD
 * é tratado pelo gate como dado inválido (candidato rejeitado), em vez de impedir o carregamento.
 */
@Entity
@Table(name = "knowledge_qa_applicability_exclusions",
        uniqueConstraints = @UniqueConstraint(name = "ux_kqa_applicability_marker",
                columnNames = {"knowledge_qa_id", "marker"}))
public class KnowledgeQaApplicabilityExclusion {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "knowledge_qa_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE) // espelha o ON DELETE CASCADE da V16 no schema gerado (H2)
    private KnowledgeQuestionAnswer questionAnswer;

    @Column(nullable = false, length = 60)
    private String marker;

    /**
     * Tipo de regra (ADR-009): {@code EXCLUDES} ou {@code REQUIRES}. Guardado como código, como o
     * marcador: um valor desconhecido é tratado pelo gate como dado inválido.
     */
    @Column(nullable = false, length = 20)
    private String kind = ApplicabilityRuleKind.EXCLUDES.name();

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(length = 255)
    private String createdBy;

    private OffsetDateTime removalRequestedAt;

    @Column(length = 255)
    private String removalRequestedBy;

    protected KnowledgeQaApplicabilityExclusion() {
    }

    public KnowledgeQaApplicabilityExclusion(
            KnowledgeQuestionAnswer questionAnswer, String marker, String note, String createdBy) {
        this(questionAnswer, marker, ApplicabilityRuleKind.EXCLUDES, note, createdBy);
    }

    public KnowledgeQaApplicabilityExclusion(KnowledgeQuestionAnswer questionAnswer, String marker,
            ApplicabilityRuleKind kind, String note, String createdBy) {
        this.questionAnswer = questionAnswer;
        this.marker = marker;
        this.kind = kind.name();
        this.note = note;
        this.createdBy = createdBy;
    }

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = OffsetDateTime.now();
    }

    /** Pede a remoção: a exclusão continua efectiva até à aprovação humana. */
    public void requestRemoval(String requestedBy) {
        this.removalRequestedBy = requestedBy;
        this.removalRequestedAt = OffsetDateTime.now();
    }

    public void cancelRemovalRequest() {
        this.removalRequestedAt = null;
        this.removalRequestedBy = null;
    }

    public boolean isRemovalPending() {
        return removalRequestedAt != null;
    }

    public UUID getId() { return id; }
    public KnowledgeQuestionAnswer getQuestionAnswer() { return questionAnswer; }
    public String getMarker() { return marker; }
    public String getKind() { return kind; }
    public String getNote() { return note; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public OffsetDateTime getRemovalRequestedAt() { return removalRequestedAt; }
    public String getRemovalRequestedBy() { return removalRequestedBy; }
}
