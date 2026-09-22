package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Aggregate counters for a governed AT-FAQ publication run (Bloco E — E8B.3).
 *
 * <p>Named with the {@code Execution} infix to distinguish the E8B.3 execution artifacts from the
 * E7 {@code AtFaqGovernedPublicationTotals} of the publication plan.
 *
 * <p>The counters double as executable guarantees. {@code indexed}, {@code embeddings} and
 * {@code ragExpected} are <b>observed from the persisted state</b> after {@code publish(...)}, not
 * assumed from the mode. Under a stub indexer they stay at zero (publication gave the knowledge no
 * voice); under a synchronous real indexer they reflect the embeddings that {@code publish(...)}
 * actually created. The invariants therefore no longer force zero — instead they encode the real
 * relationships that must always hold:
 * <ul>
 *   <li>every counter is non-negative;</li>
 *   <li>{@code embeddings == indexed} — the {@code knowledge_qa_embeddings.knowledge_qa_id} unique
 *       constraint means an indexed Q&amp;A has exactly one embedding row, so the two counts always
 *       coincide;</li>
 *   <li>{@code ragExpected <= indexed} — a Q&amp;A can only be expected in RAG results if it has an
 *       embedding.</li>
 * </ul>
 * Note that {@code indexed} is deliberately <em>not</em> bounded by {@code published}: it reflects
 * currently persisted embeddings, which may have been created on a prior run (an idempotent
 * re-execution publishes nothing yet still truthfully observes the existing embeddings).
 *
 * @param totalPersistedDrafts       persisted drafts considered by this run
 * @param eligibleForGovernedPublication drafts that passed every governed-publication guard
 * @param validated                  drafts promoted IMPORTED → VALIDATED in this run
 * @param published                  drafts published via the real service in this run
 * @param skipped                    drafts intentionally not published (not eligible, or already published)
 * @param blocked                    drafts refused by a publication guard
 * @param indexed                    Q&amp;A observed with a persisted embedding after publish (0 under a stub indexer)
 * @param embeddings                 total persisted embedding rows observed (equals {@code indexed})
 * @param ragExpected                Q&amp;A observed in a state expected to be RAG-retrievable (published + embedding + entity-eligible)
 * @param humanInterventionRequired  drafts still flagged as needing a human gate
 */
public record AtFaqGovernedPublicationExecutionTotals(
        int totalPersistedDrafts,
        int eligibleForGovernedPublication,
        int validated,
        int published,
        int skipped,
        int blocked,
        int indexed,
        int embeddings,
        int ragExpected,
        int humanInterventionRequired) {

    public AtFaqGovernedPublicationExecutionTotals {
        if (indexed < 0 || embeddings < 0 || ragExpected < 0) {
            throw new IllegalArgumentException(
                    "E9C invariant violated: indexed/embeddings/ragExpected must be non-negative");
        }
        if (embeddings != indexed) {
            throw new IllegalArgumentException(
                    "E9C invariant violated: embeddings (%d) must equal indexed (%d) — one embedding row per indexed Q&A"
                            .formatted(embeddings, indexed));
        }
        if (ragExpected > indexed) {
            throw new IllegalArgumentException(
                    "E9C invariant violated: ragExpected (%d) cannot exceed indexed (%d)"
                            .formatted(ragExpected, indexed));
        }
    }
}
