package com.knowledgeflow.ai.grounding.scope;

/** Decisão do gate de âmbito para um candidato, com o motivo (auditável, sem conteúdo fiscal). */
public record ScopeDecision(boolean allowed, ScopeReason reason) {

    public static ScopeDecision allow(ScopeReason reason) {
        return new ScopeDecision(true, reason);
    }

    public static ScopeDecision reject(ScopeReason reason) {
        return new ScopeDecision(false, reason);
    }
}
