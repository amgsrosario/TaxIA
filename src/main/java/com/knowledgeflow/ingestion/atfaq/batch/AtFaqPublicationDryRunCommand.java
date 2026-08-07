package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import java.util.List;

/**
 * A simulated, never-executed governed-publication command for a single AT-FAQ draft
 * (Bloco E — E8B.1).
 *
 * <p>"Ensaiar publicação não é publicar." This record describes what a future <b>real</b>
 * publication <i>would</i> do — which curation status it would target, whether it would persist a
 * {@code KnowledgeQuestionAnswer}, create sources, publish, or index — without doing any of it. The
 * {@code would*} flags are declared intent for E8B.2, not actions taken here. In particular
 * {@link #wouldIndex()} is always {@code false}: indexation lives in E9 and is never rehearsed.
 *
 * @param externalId            stable AT-FAQ identifier of the draft
 * @param normalizedQuestion    normalized question text of the draft
 * @param intendedAction        symbolic action the real executor would perform
 * @param intendedCurationStatus curation status a real publication would target (never applied here)
 * @param wouldPersistKnowledgeQa whether a real run would persist a {@code KnowledgeQuestionAnswer}
 * @param wouldCreateSources    whether a real run would create source references
 * @param wouldPublish          whether a real run would mark the Q&A published
 * @param wouldIndex            always {@code false}; indexation is out of scope for the dry-run
 * @param guardChecks           names of the dry-run guards that were evaluated and passed
 * @param warnings              non-blocking observations
 * @param blockingReasons       reasons a real publication would be refused (empty when eligible)
 */
public record AtFaqPublicationDryRunCommand(
        String externalId,
        String normalizedQuestion,
        String intendedAction,
        KnowledgeCurationStatus intendedCurationStatus,
        boolean wouldPersistKnowledgeQa,
        boolean wouldCreateSources,
        boolean wouldPublish,
        boolean wouldIndex,
        List<String> guardChecks,
        List<String> warnings,
        List<String> blockingReasons) {

    public AtFaqPublicationDryRunCommand {
        guardChecks = guardChecks == null ? List.of() : List.copyOf(guardChecks);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
    }
}
