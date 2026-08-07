package com.knowledgeflow.ingestion.atfaq.batch;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Governed materialization of AT-FAQ drafts (Bloco E — E8A).
 *
 * <p>"Materializar conhecimento não é publicá-lo. É preparar o objecto que poderá ser publicado."
 * Given an E7 {@link AtFaqGovernedPublicationPlan}, this service selects only the
 * {@code READY_FOR_FUTURE_PUBLICATION} candidates and turns them, via
 * {@link AtFaqKnowledgeQaDraftAssembler}, into curable {@link AtFaqMaterializationCandidate}
 * drafts held purely in memory.
 *
 * <p>It uses deterministic rules only — no database, no HTTP, no external AI/LLM. It does
 * <b>not</b> call {@code KnowledgeQuestionAnswerPublicationService}, it does <b>not</b> call
 * {@code KnowledgeQaEmbeddingIndexerImpl}, and it <b>never publishes, never indexes and never
 * persists</b>. Every produced item has {@code published == false} and {@code indexed == false};
 * the totals keep {@code published == 0} and {@code indexed == 0}. Materialization is idempotent:
 * re-running on the same plan yields an equal result and never duplicates an {@code externalId}.
 */
@Service
public class AtFaqGovernedMaterializationService {

    private final Clock clock;
    private final AtFaqKnowledgeQaDraftAssembler assembler;

    @Autowired
    public AtFaqGovernedMaterializationService() {
        this(Clock.systemUTC(), new AtFaqKnowledgeQaDraftAssembler());
    }

    /** Test constructor: fixed clock makes the timestamp deterministic. */
    AtFaqGovernedMaterializationService(Clock clock, AtFaqKnowledgeQaDraftAssembler assembler) {
        this.clock = clock;
        this.assembler = assembler;
    }

    /**
     * Materializes curable drafts from a governed publication plan. Only
     * {@code READY_FOR_FUTURE_PUBLICATION} candidates are assembled; everything else is skipped.
     * Nothing is persisted, published or indexed.
     */
    public AtFaqMaterializationResult materializeDrafts(
            AtFaqGovernedPublicationPlan plan, String materializedBy) {

        Instant materializedAt = clock.instant();
        String who = isNotBlank(materializedBy) ? materializedBy.strip() : "system";

        List<AtFaqGovernedPublicationCandidate> candidates =
                plan == null ? List.of() : plan.candidates();

        List<AtFaqMaterializationItemResult> itemResults = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        int readyFromPlan = 0;
        int materialized = 0;
        int skipped = 0;
        int blocked = 0;
        int withSources = 0;
        int withTechnical = 0;

        for (AtFaqGovernedPublicationCandidate candidate : candidates) {
            boolean ready = candidate.readiness()
                    == AtFaqGovernedPublicationReadiness.READY_FOR_FUTURE_PUBLICATION;
            if (!ready) {
                skipped++;
                continue;
            }
            readyFromPlan++;

            // Idempotency: never materialize the same externalId twice from one plan.
            if (candidate.externalId() != null && !seen.add(candidate.externalId())) {
                skipped++;
                continue;
            }

            AtFaqMaterializationItemResult result = assembler.assemble(candidate);
            itemResults.add(result);

            if (result.materialized()) {
                materialized++;
                if (result.draft() != null && !result.draft().sources().isEmpty()) {
                    withSources++;
                }
                if (result.draft() != null && isNotBlank(result.draft().technicalAnswer())) {
                    withTechnical++;
                }
            } else {
                blocked++;
            }
        }

        AtFaqMaterializationTotals totals = new AtFaqMaterializationTotals(
                candidates.size(), readyFromPlan, materialized,
                0 /* persisted */, skipped, blocked, withSources, withTechnical,
                0 /* published */, 0 /* indexed */);

        List<String> nextActions = List.of(
                "Rever rascunhos e seguir para publicação governada real (E8B). Ainda não publicados.",
                "E8A materializa rascunhos: nada foi publicado nem indexado (published=0, indexed=0).");

        String batchId = plan == null ? null : plan.batchId();

        return new AtFaqMaterializationResult(
                batchId, materializedAt, who, totals, itemResults,
                List.of(), List.of(), nextActions);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
