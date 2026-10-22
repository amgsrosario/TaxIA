package com.knowledgeflow.research.entity;

import com.knowledgeflow.ai.documented.AnswerType;
import com.knowledgeflow.ai.documented.ParecerRequirement;
import com.knowledgeflow.research.enums.InvestigationStatus;
import com.knowledgeflow.research.enums.InvestigationTrigger;
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
 * Investigação desencadeada por uma pergunta (KF-M01, ADR-010).
 * <p>
 * Regista o percurso e o resultado. A resposta guardada assenta exclusivamente em evidência de
 * investigação não promovida; por isso o tipo de resposta é, no máximo,
 * {@code CONSULTA_DOCUMENTADA_COM_LIMITACOES} com parecer {@code SUGGESTED} (Aditamento §2).
 */
@Entity
@Table(name = "research_investigations")
public class ResearchInvestigation {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_reason", nullable = false, length = 40)
    private InvestigationTrigger triggerReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvestigationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "answer_type", length = 40)
    private AnswerType answerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "parecer_requirement", length = 20)
    private ParecerRequirement parecerRequirement;

    @Column(name = "answer_text", columnDefinition = "TEXT")
    private String answerText;

    /** Razões da decisão do tipo de resposta, uma por linha. */
    @Column(name = "decision_reasons", columnDefinition = "TEXT")
    private String decisionReasons;

    /** Limitações apresentadas com a resposta, uma por linha. */
    @Column(columnDefinition = "TEXT")
    private String limitations;

    /** Pressupostos (condições a confirmar pelo utilizador), um por linha. */
    @Column(columnDefinition = "TEXT")
    private String assumptions;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Version
    private Integer version;

    protected ResearchInvestigation() {
    }

    public ResearchInvestigation(UUID organizationId, UUID requestedBy, String question,
            InvestigationTrigger triggerReason, OffsetDateTime now) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.requestedBy = requestedBy;
        this.question = question;
        this.triggerReason = triggerReason;
        this.status = triggerReason == InvestigationTrigger.RELEVANT_QA_EXISTS
                ? InvestigationStatus.NOT_STARTED
                : InvestigationStatus.OPEN;
        this.createdAt = now;
        if (status == InvestigationStatus.NOT_STARTED) {
            this.completedAt = now;
        }
    }

    public void complete(AnswerType answerType, ParecerRequirement parecerRequirement, String answerText,
            String decisionReasons, String limitations, String assumptions, OffsetDateTime now) {
        if (answerType != AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES && answerType != AnswerType.RESPOSTA_LIMITE) {
            throw new IllegalArgumentException("Evidência não promovida só admite CONSULTA_DOCUMENTADA_COM_LIMITACOES"
                    + " ou RESPOSTA_LIMITE: " + answerType);
        }
        if (parecerRequirement == null || parecerRequirement == ParecerRequirement.NONE) {
            throw new IllegalArgumentException("Evidência não promovida exige parecer SUGGESTED ou REQUIRED");
        }
        this.status = InvestigationStatus.COMPLETED;
        this.answerType = answerType;
        this.parecerRequirement = parecerRequirement;
        this.answerText = answerText;
        this.decisionReasons = decisionReasons;
        this.limitations = limitations;
        this.assumptions = assumptions;
        this.completedAt = now;
    }

    public void fail(String reason, OffsetDateTime now) {
        this.status = InvestigationStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getRequestedBy() { return requestedBy; }
    public String getQuestion() { return question; }
    public InvestigationTrigger getTriggerReason() { return triggerReason; }
    public InvestigationStatus getStatus() { return status; }
    public AnswerType getAnswerType() { return answerType; }
    public ParecerRequirement getParecerRequirement() { return parecerRequirement; }
    public String getAnswerText() { return answerText; }
    public String getDecisionReasons() { return decisionReasons; }
    public String getLimitations() { return limitations; }
    public String getAssumptions() { return assumptions; }
    public String getFailureReason() { return failureReason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
}
