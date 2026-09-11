package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Aggregate counters for a governed AT-FAQ RAG indexing run (Bloco E — E9A/E9B).
 *
 * <p>The caps are <b>mode-aware</b> and encode each frase-mestra structurally:
 * <ul>
 *   <li>{@link AtFaqRagIndexingMode#TEST_ISOLATED_SINGLE_QA} (E9A) keeps the hard single-Q&amp;A cap:
 *       {@code indexed}, {@code embeddingRows} and {@code ragExpected} are each 0 or 1;</li>
 *   <li>{@link AtFaqRagIndexingMode#SMALL_BATCH_TEST_ISOLATED} (E9B) raises that to the small-batch
 *       ceiling of {@value #MAX_SMALL_BATCH} — "indexar vários não é escalar livremente": more than
 *       one, but never more than three.</li>
 * </ul>
 *
 * @param mode                execution mode that governs the caps below
 * @param totalPublishedItems number of published items seen in the incoming publication result
 * @param eligibleForIndexing number of those items that passed every indexing guard
 * @param indexed             embeddings written in this run (≤ 1 single, ≤ 3 small batch)
 * @param skipped             eligible items deferred (single-Q&amp;A policy or small-batch limit)
 * @param blocked             items that looked publishable but failed an indexing guard
 * @param embeddingRows       embedding rows observed for the indexed Q&amp;As (matches {@code indexed})
 * @param ragExpected         Q&amp;As expected to be RAG-retrievable after this run (matches {@code indexed})
 */
public record AtFaqRagIndexingTotals(
        AtFaqRagIndexingMode mode,
        int totalPublishedItems,
        int eligibleForIndexing,
        int indexed,
        int skipped,
        int blocked,
        int embeddingRows,
        int ragExpected) {

    /** Small-batch ceiling (E9B): never index more than three Q&amp;A in one governed run. */
    public static final int MAX_SMALL_BATCH = 3;

    public AtFaqRagIndexingTotals {
        if (mode == null) {
            throw new IllegalArgumentException("mode is required for indexing totals");
        }
        int cap = mode == AtFaqRagIndexingMode.SMALL_BATCH_TEST_ISOLATED ? MAX_SMALL_BATCH : 1;
        if (indexed < 0 || indexed > cap) {
            throw new IllegalArgumentException(
                    "indexed out of range for " + mode + " (cap " + cap + "): indexed=" + indexed);
        }
        if (embeddingRows < 0 || embeddingRows > cap) {
            throw new IllegalArgumentException(
                    "embeddingRows out of range for " + mode + " (cap " + cap + "): embeddingRows="
                            + embeddingRows);
        }
        if (ragExpected < 0 || ragExpected > cap) {
            throw new IllegalArgumentException(
                    "ragExpected out of range for " + mode + " (cap " + cap + "): ragExpected="
                            + ragExpected);
        }
    }
}
