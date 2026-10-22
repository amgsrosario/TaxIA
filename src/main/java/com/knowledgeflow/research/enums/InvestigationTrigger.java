package com.knowledgeflow.research.enums;

/** Porque é que a investigação foi (ou não) aberta: só uma pergunta sem Q&amp;A publicada relevante é investigada (AR2-DEC-KF-2026-10-22 §8). */
public enum InvestigationTrigger {
    NO_RELEVANT_QA,
    RELEVANT_QA_EXISTS,
    /** A verificação do corpus falhou (ex.: serviço de embeddings indisponível); investiga-se na mesma. */
    CORPUS_CHECK_UNAVAILABLE
}
