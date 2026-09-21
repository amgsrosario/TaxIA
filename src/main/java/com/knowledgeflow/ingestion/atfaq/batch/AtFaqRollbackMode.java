package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Execution mode for governed AT-FAQ rollback (Bloco E — E10A/E10B).
 *
 * <p>E10A frase-mestra: "Retirar voz não é apagar conhecimento. É neutralizar a recuperação
 * preservando rasto e motivo." E10B frase-mestra: "Calar uma voz prova o travão. Calar um pequeno
 * coro prova a governação."
 *
 * <p>Both values are deliberately confined to an isolated Testcontainers database, rolling back
 * published/indexed Q&amp;A through the <b>real</b>
 * {@code KnowledgeQuestionAnswerPublicationService#unpublish} fed by the <b>real</b>
 * {@code KnowledgeQaEmbeddingIndexerImpl} — never a recurring job, never the real pilot base, never
 * an external provider. Massive rollback stays out of scope: E10B caps the batch at three.
 */
public enum AtFaqRollbackMode {

    /**
     * E10A: roll back exactly one published/indexed Q&amp;A in an isolated DB — despublicar and
     * desindexar as distinct-but-coordinated effects, with a mandatory reason.
     */
    TEST_ISOLATED_SINGLE_QA,

    /**
     * E10B: roll back a governed <em>small batch</em> of published/indexed Q&amp;A in an isolated DB
     * — more than one, but never more than {@link AtFaqRollbackTotals#MAX_SMALL_BATCH}. Every
     * per-Q&amp;A guard of the single case applies unchanged; a mandatory reason governs the whole
     * batch; eligible items beyond the limit are deferred, never dropped. "Calar um pequeno coro
     * prova a governação" — not mass silencing.
     */
    SMALL_BATCH_TEST_ISOLATED
}
