package com.knowledgeflow.ingestion.atfaq.batch;

import java.util.List;
import java.util.UUID;

/**
 * The per-item outcome of governed AT-FAQ draft materialization (Bloco E — E8A).
 *
 * <p>Records whether an item was materialized into a curable {@link AtFaqMaterializationCandidate}
 * and — invariantly for E8A — that it was <b>not</b> published and <b>not</b> indexed. When
 * assembly guards fail, {@code materialized} is false and {@code blockingReasons} explains why.
 *
 * <p>Invariants (E8A): {@code published == false} and {@code indexed == false} always. In this
 * in-memory pass {@code persisted == false} and {@code knowledgeQaId == null}; a persisted draft
 * (future, isolated-DB only) would still be non-published and non-indexed.
 *
 * @param externalId       source id of the item
 * @param materialized     whether a draft was assembled
 * @param persisted        whether the draft was persisted (always false in the in-memory pass)
 * @param published        always false — E8A never publishes
 * @param indexed          always false — E8A never indexes
 * @param knowledgeQaId    id of the persisted draft, or null when not persisted
 * @param normalizedQuestion whitespace-stable question (echoed for auditing)
 * @param draft            the assembled draft, or null when not materialized
 * @param warnings         non-blocking notes
 * @param blockingReasons  reasons the item could not be materialized (empty when materialized)
 * @param nextActions      recommended follow-up (never publication in E8A)
 */
public record AtFaqMaterializationItemResult(
        String externalId,
        boolean materialized,
        boolean persisted,
        boolean published,
        boolean indexed,
        UUID knowledgeQaId,
        String normalizedQuestion,
        AtFaqMaterializationCandidate draft,
        List<String> warnings,
        List<String> blockingReasons,
        List<String> nextActions) {

    public AtFaqMaterializationItemResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
    }
}
