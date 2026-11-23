package com.knowledgeflow.research.entity;

import com.knowledgeflow.research.enums.ResearchPhase;
import com.knowledgeflow.research.enums.StepOrigin;
import com.knowledgeflow.research.enums.StepOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Passo de uma investigação (append-only): distingue descoberta, consulta e confirmação.
 * Nunca é alterado depois de gravado.
 */
@Entity
@Table(name = "research_steps")
public class ResearchStep {

    @Id
    private UUID id;

    @Column(name = "investigation_id", nullable = false)
    private UUID investigationId;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResearchPhase phase;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StepOrigin origin;

    @Column(length = 255)
    private String target;

    @Column(length = 2000)
    private String url;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StepOutcome outcome;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    /** Proveniência da chamada ao modelo (origem MODEL); nula nos restantes passos. */
    @Column(name = "model_provider", length = 60)
    private String modelProvider;

    @Column(name = "model_name", length = 120)
    private String modelName;

    @Column(name = "model_input_tokens")
    private Integer modelInputTokens;

    @Column(name = "model_output_tokens")
    private Integer modelOutputTokens;

    @Column(name = "model_duration_ms")
    private Long modelDurationMs;

    protected ResearchStep() {
    }

    public ResearchStep(UUID investigationId, int stepOrder, ResearchPhase phase, StepOrigin origin,
            String target, String url, Integer httpStatus, StepOutcome outcome, String detail,
            OffsetDateTime occurredAt) {
        this.id = UUID.randomUUID();
        this.investigationId = investigationId;
        this.stepOrder = stepOrder;
        this.phase = phase;
        this.origin = origin;
        this.target = target;
        this.url = url;
        this.httpStatus = httpStatus;
        this.outcome = outcome;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    /** Passo de uma chamada ao modelo: provider, modelo, tokens e duração; nunca prompt nem resposta. */
    public static ResearchStep modelCall(UUID investigationId, int stepOrder, ResearchPhase phase, String target,
            com.knowledgeflow.research.service.ModelCall call, OffsetDateTime occurredAt) {
        ResearchStep step = new ResearchStep(investigationId, stepOrder, phase, StepOrigin.MODEL, target, null, null,
                call.outcome(), call.detail(), occurredAt);
        step.modelProvider = call.provider();
        step.modelName = call.model();
        step.modelInputTokens = call.inputTokens();
        step.modelOutputTokens = call.outputTokens();
        step.modelDurationMs = call.durationMs();
        return step;
    }

    public UUID getId() { return id; }
    public UUID getInvestigationId() { return investigationId; }
    public int getStepOrder() { return stepOrder; }
    public ResearchPhase getPhase() { return phase; }
    public StepOrigin getOrigin() { return origin; }
    public String getTarget() { return target; }
    public String getUrl() { return url; }
    public Integer getHttpStatus() { return httpStatus; }
    public StepOutcome getOutcome() { return outcome; }
    public String getDetail() { return detail; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public String getModelProvider() { return modelProvider; }
    public String getModelName() { return modelName; }
    public Integer getModelInputTokens() { return modelInputTokens; }
    public Integer getModelOutputTokens() { return modelOutputTokens; }
    public Long getModelDurationMs() { return modelDurationMs; }
}
