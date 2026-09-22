package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Per-draft outcome of governed AT-FAQ publication (Bloco E — E8B.3).
 *
 * <p>Named with the {@code Execution} infix to distinguish the E8B.3 execution artifacts from the
 * E7 publication-plan family that shares the {@code AtFaqGovernedPublication} prefix.
 *
 * <p>Each persisted draft resolves to exactly one shape:
 * <ul>
 *   <li><b>published</b> — future-autonomy eligible, promoted IMPORTED → VALIDATED, and published
 *       via the real service ({@code published == true}, carries {@code publishedAt}/
 *       {@code publishedBy} and {@code curationStatus == VALIDATED});</li>
 *   <li><b>skipped</b> — not eligible for governed publication, or already published on a previous
 *       run (idempotent reuse); {@code published} reflects the persisted state;</li>
 *   <li><b>blocked</b> — eligible-looking but failed a publication guard; carries
 *       {@code blockingReasons}.</li>
 * </ul>
 *
 * <p>{@code indexed}, {@code embeddingPresent} and {@code ragExpectedToRetrieve} are <b>observed
 * from the persisted state</b> after {@code publish(...)}, never assumed from the mode. Under a stub
 * indexer they stay {@code false} (publication reached {@code publishedAt}/{@code publishedBy} but
 * produced no embedding, so the RAG can never retrieve the case); under a synchronous real indexer
 * they become {@code true} because {@code publish(...)} created the embedding in the same
 * transaction. Two invariants always hold, encoding what those flags mean:
 * <ul>
 *   <li>{@code indexed == embeddingPresent} — "indexed" means exactly one embedding row exists (the
 *       {@code knowledge_qa_id} unique constraint guarantees it is never more than one);</li>
 *   <li>if {@code ragExpectedToRetrieve} then {@code published && embeddingPresent} — RAG
 *       retrievability requires both a publication and a real embedding.</li>
 * </ul>
 *
 * @param externalId                  stable AT-FAQ identifier
 * @param knowledgeQaId               id of the persisted Q&amp;A, or {@code null}
 * @param eligibleForGovernedPublication whether every governed-publication guard passed
 * @param validated                   whether the draft was promoted to VALIDATED in this run
 * @param published                   whether the Q&amp;A is published (now, or from a prior run)
 * @param indexed                     whether a persisted embedding was observed (equals {@code embeddingPresent})
 * @param publishedBy                 publisher name recorded on the Q&amp;A, or {@code null}
 * @param publishedAt                 publication instant recorded on the Q&amp;A, or {@code null}
 * @param curationStatus              curation status observed after the run
 * @param eligibleForRagByEntityRules result of the entity's own {@code isEligibleForRag()} check
 * @param embeddingPresent            whether exactly one embedding row exists for the Q&amp;A
 * @param ragExpectedToRetrieve       whether the Q&amp;A ended in a state expected to be RAG-retrievable
 * @param autonomySignals             audit signals carried from persistence classification
 * @param warnings                    non-blocking observations
 * @param blockingReasons             reasons publication was refused (empty when published/skipped-clean)
 * @param nextActions                 recommended follow-ups
 */
public record AtFaqGovernedPublicationExecutionItemResult(
        String externalId,
        UUID knowledgeQaId,
        boolean eligibleForGovernedPublication,
        boolean validated,
        boolean published,
        boolean indexed,
        String publishedBy,
        Instant publishedAt,
        KnowledgeCurationStatus curationStatus,
        boolean eligibleForRagByEntityRules,
        boolean embeddingPresent,
        boolean ragExpectedToRetrieve,
        List<String> autonomySignals,
        List<String> warnings,
        List<String> blockingReasons,
        List<String> nextActions) {

    public AtFaqGovernedPublicationExecutionItemResult {
        autonomySignals = autonomySignals == null ? List.of() : List.copyOf(autonomySignals);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
        if (indexed != embeddingPresent) {
            throw new IllegalArgumentException(
                    "E9C invariant violated: indexed must equal embeddingPresent for " + externalId);
        }
        if (ragExpectedToRetrieve && !(published && embeddingPresent)) {
            throw new IllegalArgumentException(
                    "E9C invariant violated: ragExpectedToRetrieve requires published + embeddingPresent for "
                            + externalId);
        }
    }
}
