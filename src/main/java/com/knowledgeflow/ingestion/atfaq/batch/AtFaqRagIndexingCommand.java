package com.knowledgeflow.ingestion.atfaq.batch;

import java.util.List;
import java.util.UUID;

/**
 * Inspectable decision to index one published Q&amp;A into the RAG vector store (Bloco E — E9A).
 *
 * <p>Frase-mestra: "Indexar não é escalar. É dar voz controlada a um único conhecimento publicado."
 * The command is the governance gate between an E8B.3 publication outcome and an actual write to
 * {@code knowledge_qa_embeddings}: {@code index} is only ever true when every guard passed, the run
 * is restricted to a single Q&amp;A, and no production data is in play.
 *
 * @param externalId          stable AT-FAQ identifier of the candidate
 * @param knowledgeQaId       id of the published Q&amp;A to index, or {@code null} when not resolvable
 * @param normalizedQuestion  normalized question carried for traceability (never a raw vector/prompt)
 * @param index               whether this Q&amp;A should actually be indexed
 * @param singleQaOnly        always {@code true}; E9A never indexes more than one Q&amp;A
 * @param productionDataAllowed always {@code false}; E9A only ever runs against an isolated DB
 * @param guardChecks         human-readable guard checks that passed
 * @param warnings            non-blocking observations
 * @param blockingReasons     reasons indexing is refused (must be empty when {@code index} is true)
 */
public record AtFaqRagIndexingCommand(
        String externalId,
        UUID knowledgeQaId,
        String normalizedQuestion,
        boolean index,
        boolean singleQaOnly,
        boolean productionDataAllowed,
        List<String> guardChecks,
        List<String> warnings,
        List<String> blockingReasons) {

    public AtFaqRagIndexingCommand {
        guardChecks = guardChecks == null ? List.of() : List.copyOf(guardChecks);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
        if (index && !blockingReasons.isEmpty()) {
            throw new IllegalArgumentException(
                    "E9A cannot index with blocking reasons present: " + externalId);
        }
        if (!singleQaOnly) {
            throw new IllegalArgumentException(
                    "E9A is single-Q&A only: singleQaOnly must be true for " + externalId);
        }
        if (productionDataAllowed) {
            throw new IllegalArgumentException(
                    "E9A never touches production data: productionDataAllowed must be false for "
                            + externalId);
        }
    }
}
