package com.knowledgeflow.interactions.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "assisted_interaction_messages")
public class AssistedInteractionMessage {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "interaction_id", nullable = false)
    private AssistedInteraction interaction;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer;

    @Column(name = "model_used", length = 80)
    private String modelUsed;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AssistedInteractionMessage() {}

    public AssistedInteractionMessage(AssistedInteraction interaction, String question,
                                      String answer, String modelUsed,
                                      int inputTokens, int outputTokens) {
        this.interaction = interaction;
        this.question = question;
        this.answer = answer;
        this.modelUsed = modelUsed;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    @PrePersist
    void onCreate() {
        if (this.id == null) this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public AssistedInteraction getInteraction() { return interaction; }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public String getModelUsed() { return modelUsed; }
    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public Instant getCreatedAt() { return createdAt; }
}
