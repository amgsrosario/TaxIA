package com.knowledgeflow.research.entity;

import com.knowledgeflow.research.enums.EntailmentDecision;
import com.knowledgeflow.research.service.EntailmentValidator.Dimension;
import com.knowledgeflow.research.service.EntailmentValidator.DimensionStatus;
import com.knowledgeflow.research.service.EntailmentValidator.Verdict;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Validação de implicação jurídica de uma proposição (KF-M02/ARCH-01): veredicto do validador,
 * dimensões avaliadas, justificação, passagem citada e sua verificação na evidência oficial, e a
 * decisão final determinística. Append-only.
 */
@Entity
@Table(name = "research_entailment_checks")
public class EntailmentCheck {

    @Id
    private UUID id;

    @Column(name = "investigation_id", nullable = false)
    private UUID investigationId;

    @Column(name = "proposition_id", nullable = false)
    private UUID propositionId;

    @Column(name = "evidence_id", nullable = false)
    private UUID evidenceId;

    /** Passo da chamada ao modelo; nulo se a barreira não chegou a chamar o modelo. */
    @Column(name = "model_step_id")
    private UUID modelStepId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Verdict verdict;

    /** Uma linha por dimensão: {@code DIMENSAO=ESTADO|nota}. */
    @Column(columnDefinition = "TEXT")
    private String dimensions;

    @Column(columnDefinition = "TEXT")
    private String justification;

    @Column(name = "cited_passage", columnDefinition = "TEXT")
    private String citedPassage;

    @Column(name = "citation_verified", nullable = false)
    private boolean citationVerified;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EntailmentDecision decision;

    @Column(name = "decision_reason", nullable = false, columnDefinition = "TEXT")
    private String decisionReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected EntailmentCheck() {
    }

    public EntailmentCheck(UUID investigationId, UUID propositionId, UUID evidenceId, UUID modelStepId, Verdict verdict,
            Map<Dimension, DimensionStatus> dimensions, Map<Dimension, String> notes, String justification,
            String citedPassage, boolean citationVerified, EntailmentDecision decision, String decisionReason,
            OffsetDateTime now) {
        if (decision == EntailmentDecision.DEMONSTRATED && (verdict != Verdict.SUPORTA || !citationVerified)) {
            throw new IllegalArgumentException("Implicação demonstrada exige SUPORTA e passagem verificada");
        }
        this.id = UUID.randomUUID();
        this.investigationId = investigationId;
        this.propositionId = propositionId;
        this.evidenceId = evidenceId;
        this.modelStepId = modelStepId;
        this.verdict = verdict;
        this.dimensions = format(dimensions, notes);
        this.justification = justification;
        this.citedPassage = citedPassage;
        this.citationVerified = citationVerified;
        this.decision = decision;
        this.decisionReason = decisionReason;
        this.createdAt = now;
    }

    private static String format(Map<Dimension, DimensionStatus> dimensions, Map<Dimension, String> notes) {
        if (dimensions == null || dimensions.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Dimension d : Dimension.values()) {
            DimensionStatus s = dimensions.get(d);
            if (s == null) continue;
            String note = notes == null ? null : notes.get(d);
            sb.append(d.name()).append('=').append(s.name());
            if (note != null && !note.isBlank()) sb.append('|').append(note.replace('\n', ' '));
            sb.append('\n');
        }
        return sb.toString().strip();
    }

    /** Dimensões registadas (para re-verificação independente da decisão). */
    public Map<Dimension, DimensionStatus> dimensionStatuses() {
        Map<Dimension, DimensionStatus> out = new EnumMap<>(Dimension.class);
        if (dimensions == null) return out;
        for (String line : dimensions.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            int bar = line.indexOf('|', eq);
            try {
                out.put(Dimension.valueOf(line.substring(0, eq).strip()),
                        DimensionStatus.valueOf((bar < 0 ? line.substring(eq + 1) : line.substring(eq + 1, bar)).strip()));
            } catch (IllegalArgumentException ignored) {
                // linha ilegível: dimensão tratada como não avaliada
            }
        }
        return out;
    }

    public UUID getId() { return id; }
    public UUID getInvestigationId() { return investigationId; }
    public UUID getPropositionId() { return propositionId; }
    public UUID getEvidenceId() { return evidenceId; }
    public UUID getModelStepId() { return modelStepId; }
    public Verdict getVerdict() { return verdict; }
    public String getDimensions() { return dimensions; }
    public String getJustification() { return justification; }
    public String getCitedPassage() { return citedPassage; }
    public boolean isCitationVerified() { return citationVerified; }
    public EntailmentDecision getDecision() { return decision; }
    public String getDecisionReason() { return decisionReason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
