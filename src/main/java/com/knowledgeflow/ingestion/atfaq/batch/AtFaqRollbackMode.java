package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Execution mode for governed AT-FAQ rollback (Bloco E — E10A).
 *
 * <p>Frase-mestra: "Retirar voz não é apagar conhecimento. É neutralizar a recuperação preservando
 * rasto e motivo."
 *
 * <p>The only value is deliberately confined to an isolated Testcontainers database, rolling back
 * exactly one published/indexed Q&amp;A through the <b>real</b>
 * {@code KnowledgeQuestionAnswerPublicationService#unpublish} fed by the <b>real</b>
 * {@code KnowledgeQaEmbeddingIndexerImpl} — never a recurring job, never a batch, never the real
 * pilot base, never an external provider. Batch rollback is E10B; it is explicitly out of scope
 * here.
 */
public enum AtFaqRollbackMode {

    /**
     * E10A: roll back exactly one published/indexed Q&amp;A in an isolated DB — despublicar and
     * desindexar as distinct-but-coordinated effects, with a mandatory reason.
     */
    TEST_ISOLATED_SINGLE_QA
}
