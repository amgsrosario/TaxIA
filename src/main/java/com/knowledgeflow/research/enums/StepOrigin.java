package com.knowledgeflow.research.enums;

/** Origem de um passo. Uma hipótese do modelo nunca é evidência: só a confirmação na fonte oficial o é. */
public enum StepOrigin {
    CORPUS,
    QUESTION_REFERENCE,
    MODEL_HYPOTHESIS,
    CRITICAL_MEMORY,
    OFFICIAL_SOURCE
}
