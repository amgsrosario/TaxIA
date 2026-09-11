package com.knowledgeflow.ingestion.atfaq.batch;

import java.util.UUID;

/**
 * Read-only probe that answers whether the RAG currently retrieves a given Q&amp;A (Bloco E — E10A).
 *
 * <p>Frase-mestra: "Retirar voz não é apagar conhecimento. É neutralizar a recuperação preservando
 * rasto e motivo." Proving a rollback means proving that retrieval stops — so the rollback service
 * needs a way to ask "does the RAG still recover this Q&amp;A?" <em>before</em> and <em>after</em>
 * the unpublish.
 *
 * <p>This is a deliberately small seam so {@link AtFaqGovernedRollbackService} keeps no hard
 * dependency on {@code RagSearchService}, on a query string, or on any embedding model. In the
 * isolated integration test the probe is backed by a real {@code RagSearchService} fed by a
 * deterministic in-test embedding and a semantically compatible question; in production the
 * capability does not exist yet (E10A is test-isolated), so the service may be constructed without a
 * probe and will then refuse to roll back rather than claim an unverified retrieval state.
 */
@FunctionalInterface
public interface AtFaqRollbackRagProbe {

    /**
     * @param organizationId owning organization (never the real pilot base outside tests)
     * @param knowledgeQaId  id of the Q&amp;A to look for among RAG results
     * @return {@code true} if the RAG currently retrieves this Q&amp;A for its own question
     */
    boolean recovers(UUID organizationId, UUID knowledgeQaId);
}
