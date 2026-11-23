package com.knowledgeflow.research.service;

import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.research.enums.StepOutcome;

/**
 * Proveniência de uma chamada ao modelo no percurso de investigação (KF-M02): provider, modelo,
 * tokens, duração e resultado. Nunca contém prompts, respostas nem credenciais.
 */
public record ModelCall(
        String provider,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        Long durationMs,
        StepOutcome outcome,
        String detail) {

    public static ModelCall completed(AIResponse r) {
        return new ModelCall(r.provider(), r.modelUsed(), r.inputTokens(), r.outputTokens(), r.durationMillis(),
                StepOutcome.MODEL_COMPLETED, null);
    }

    public static ModelCall unparseable(AIResponse r, String detail) {
        return new ModelCall(r.provider(), r.modelUsed(), r.inputTokens(), r.outputTokens(), r.durationMillis(),
                StepOutcome.MODEL_UNPARSEABLE, detail);
    }

    public static ModelCall failed(ModelIdentity.Identity identity, RuntimeException e) {
        return new ModelCall(identity.provider(), identity.model(), null, null, null, StepOutcome.MODEL_FAILED,
                "Falha do modelo: " + e.getClass().getSimpleName());
    }
}
