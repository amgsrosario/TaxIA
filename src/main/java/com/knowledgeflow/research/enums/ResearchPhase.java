package com.knowledgeflow.research.enums;

/** Fase de um passo: verificação do corpus, descoberta (hipótese), consulta (obtenção da fonte) e confirmação (a fonte contém o que se procurava). */
public enum ResearchPhase {
    CORPUS_CHECK,
    DISCOVERY,
    CONSULTATION,
    CONFIRMATION,
    /** Chamada ao modelo para estruturação (proposições, condições, excepções, conflitos). */
    STRUCTURING,
    /** Segunda barreira: validação da implicação jurídica de cada proposição (KF-M02/ARCH-01). */
    ENTAILMENT,
    /** Chamada ao modelo para redacção da resposta. */
    ANSWER_COMPOSITION
}
