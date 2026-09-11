package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Aggregate counters for a governed AT-FAQ RAG indexing run (Bloco E — E9A).
 *
 * <p>The hard caps ({@code indexed <= 1}, {@code embeddingRows <= 1}, {@code ragExpected <= 1})
 * encode the frase-mestra structurally: E9A gives controlled voice to a single published Q&amp;A, so
 * no run can ever index, embed or make retrievable more than one case.
 *
 * @param totalPublishedItems number of published items seen in the incoming publication result
 * @param eligibleForIndexing number of those items that passed every indexing guard
 * @param indexed             embeddings written in this run (0 or 1)
 * @param skipped             published items deferred (single-Q&A policy) or already carrying an embedding
 * @param blocked             items that looked eligible but failed a guard
 * @param embeddingRows       embedding rows observed for the indexed Q&amp;A (0 or 1)
 * @param ragExpected         Q&amp;As expected to be RAG-retrievable after this run (0 or 1)
 */
public record AtFaqRagIndexingTotals(
        int totalPublishedItems,
        int eligibleForIndexing,
        int indexed,
        int skipped,
        int blocked,
        int embeddingRows,
        int ragExpected) {

    public AtFaqRagIndexingTotals {
        if (indexed < 0 || indexed > 1) {
            throw new IllegalArgumentException("E9A indexes at most one Q&A: indexed=" + indexed);
        }
        if (embeddingRows < 0 || embeddingRows > 1) {
            throw new IllegalArgumentException(
                    "E9A writes at most one embedding: embeddingRows=" + embeddingRows);
        }
        if (ragExpected < 0 || ragExpected > 1) {
            throw new IllegalArgumentException(
                    "E9A makes at most one Q&A retrievable: ragExpected=" + ragExpected);
        }
    }
}
