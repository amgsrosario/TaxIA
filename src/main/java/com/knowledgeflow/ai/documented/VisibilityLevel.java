package com.knowledgeflow.ai.documented;

/**
 * Nível de visibilidade/projecção da resposta documentada (Decisões C1/C7).
 *
 * <p>Não significa "cliente leigo vs. profissional": {@code EXTERNAL}/{@code DEMO}
 * recebem produto profissional limpo; {@code INTERNAL}/{@code CURATION_ONLY} podem, por
 * cima, incluir diagnóstico (bastidores). A diferença entre níveis é o grau de exposição
 * dos bastidores, não a qualidade conceptual da resposta.
 *
 * <p>Contrato: docs/taxia-documented-answer-contract.md (D3), secção 7.
 */
public enum VisibilityLevel {

    /** Utilizador externo profissional. */
    EXTERNAL,

    /** Ambiente profissional limitado comercialmente (mesma qualidade). */
    DEMO,

    /** Vista interna com diagnóstico. */
    INTERNAL,

    /** Vista de curadoria com diagnóstico e bastidores. */
    CURATION_ONLY
}
