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
 * <p>Whatever the shape, three invariants always hold, asserting that E8B.3 gives the knowledge no
 * "voice": {@code indexed == false}, {@code embeddingPresent == false} and
 * {@code ragExpectedToRetrieve == false}. Publication reaches {@code publishedAt}/{@code publishedBy}
 * but, under the stub indexer, produces no embedding, so the RAG can never retrieve the case.
 *
 * @param externalId                  stable AT-FAQ identifier
 * @param knowledgeQaId               id of the persisted Q&amp;A, or {@code null}
 * @param eligibleForGovernedPublication whether every governed-publication guard passed
 * @param validated                   whether the draft was promoted to VALIDATED in this run
 * @param published                   whether the Q&amp;A is published (now, or from a prior run)
 * @param indexed                     always {@code false}
 * @param publishedBy                 publisher name recorded on the Q&amp;A, or {@code null}
 * @param publishedAt                 publication instant recorded on the Q&amp;A, or {@code null}
 * @param curationStatus              curation status observed after the run
 * @param eligibleForRagByEntityRules result of the entity's own {@code isEligibleForRag()} check
 * @param embeddingPresent            always {@code false} (no embedding row is ever created)
 * @param ragExpectedToRetrieve       always {@code false} (no embedding → not retrievable)
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
        if (indexed) {
            throw new IllegalArgumentException(
                    "E8B.3 must never mark an item indexed: " + externalId);
        }
        if (embeddingPresent) {
            throw new IllegalArgumentException(
                    "E8B.3 must never observe an embedding: " + externalId);
        }
        if (ragExpectedToRetrieve) {
            throw new IllegalArgumentException(
                    "E8B.3 must never expect RAG retrieval: " + externalId);
        }
    }
}
