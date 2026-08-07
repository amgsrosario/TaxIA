package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.ai.documented.FreshnessStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Builds a governed publication <b>plan</b> over a pre-curated + reviewed AT-FAQ batch
 * (Bloco E — E7).
 *
 * <p>"Planear publicação não é publicar. É provar que nada passa sem guardas." Given the E5
 * pre-curation ({@link AtFaqPreCurationResult}) and the E6 review ({@link AtFaqReviewResult}),
 * this service cross-references items by {@code externalId}, re-applies the future-publication
 * guards and records, per item, how far it could proceed towards a later real publication step.
 *
 * <p>It uses deterministic rules only — no database, no HTTP, no external AI/LLM. It does
 * <b>not</b> call {@code KnowledgeQuestionAnswerPublicationService}, it does <b>not</b> call
 * {@code KnowledgeQaEmbeddingIndexerImpl}, and it <b>never publishes, never indexes and never
 * persists</b>. A readiness of {@code READY_FOR_FUTURE_PUBLICATION} means only that every guard
 * passed — the real PUBLISH_GOVERNED step stays out of scope (E8+).
 *
 * <p>Prudence rule: the plan readiness is never less prudent than the E6 review outcome.
 */
@Service
public class AtFaqGovernedPublicationPlanService {

    private final Clock clock;

    @Autowired
    public AtFaqGovernedPublicationPlanService() {
        this(Clock.systemUTC());
    }

    /** Test constructor: fixed clock makes the plan timestamp deterministic. */
    AtFaqGovernedPublicationPlanService(Clock clock) {
        this.clock = clock;
    }

    /**
     * Produces a governed publication plan. Items are processed in pre-curation order; the review
     * outcome is matched by {@code externalId}.
     */
    public AtFaqGovernedPublicationPlan plan(
            AtFaqPreCurationResult preCurationResult,
            AtFaqReviewResult reviewResult,
            String plannedBy) {

        Instant plannedAt = clock.instant();
        String who = isNotBlank(plannedBy) ? plannedBy.strip() : "system";

        List<AtFaqPreCuratedBatchItem> preItems =
                preCurationResult == null ? List.of() : preCurationResult.items();
        List<AtFaqReviewItemResult> reviewItems =
                reviewResult == null ? List.of() : reviewResult.itemResults();

        Map<String, AtFaqReviewItemResult> reviewById = new LinkedHashMap<>();
        for (AtFaqReviewItemResult r : reviewItems) {
            if (r != null && r.externalId() != null) {
                reviewById.putIfAbsent(r.externalId(), r);
            }
        }
        Set<String> preIds = new LinkedHashSet<>();
        for (AtFaqPreCuratedBatchItem p : preItems) {
            preIds.add(p.externalId());
        }

        List<String> globalWarnings = new ArrayList<>();
        // Review items with no matching pre-curation item cannot be planned.
        for (AtFaqReviewItemResult r : reviewItems) {
            if (r != null && r.externalId() != null && !preIds.contains(r.externalId())) {
                globalWarnings.add("Revisão sem pré-curadoria correspondente ignorada: " + r.externalId() + ".");
            }
        }

        List<AtFaqGovernedPublicationCandidate> candidates = new ArrayList<>(preItems.size());
        int ready = 0;
        int assisted = 0;
        int manual = 0;
        int blocked = 0;
        int deferred = 0;
        int withTechnical = 0;
        int withLegal = 0;
        int withSources = 0;
        int withWarnings = 0;

        for (AtFaqPreCuratedBatchItem pre : preItems) {
            AtFaqReviewItemResult review = reviewById.get(pre.externalId());
            AtFaqGovernedPublicationCandidate candidate = planItem(pre, review);
            candidates.add(candidate);

            switch (candidate.readiness()) {
                case READY_FOR_FUTURE_PUBLICATION -> ready++;
                case NEEDS_ASSISTED_REVIEW -> assisted++;
                case NEEDS_MANUAL_REVIEW -> manual++;
                case BLOCKED -> blocked++;
                case DEFERRED -> deferred++;
            }
            if (isNotBlank(pre.proposedTechnicalAnswer())) {
                withTechnical++;
            }
            if (!pre.proposedLegalReferences().isEmpty()) {
                withLegal++;
            }
            if (!pre.proposedSources().isEmpty()) {
                withSources++;
            }
            if (!candidate.guardResult().warnings().isEmpty()) {
                withWarnings++;
            }
        }

        AtFaqGovernedPublicationTotals totals = new AtFaqGovernedPublicationTotals(
                preItems.size(), ready, assisted, manual, blocked, deferred,
                withTechnical, withLegal, withSources, withWarnings,
                0 /* published */, 0 /* indexed */);

        List<String> nextActions = buildGlobalNextActions(totals);
        String batchId = preCurationResult != null ? preCurationResult.batchId()
                : (reviewResult != null ? reviewResult.batchId() : null);

        return new AtFaqGovernedPublicationPlan(
                batchId, plannedAt, who, totals, candidates, globalWarnings, List.of(), nextActions);
    }

    private AtFaqGovernedPublicationCandidate planItem(
            AtFaqPreCuratedBatchItem pre, AtFaqReviewItemResult review) {

        AtFaqGovernedPublicationGuardResult guard = evaluateGuards(pre, review);
        AtFaqGovernedPublicationReadiness readiness = decideReadiness(pre, review, guard);

        List<String> sourceSummaries = summarizeSources(pre);
        List<String> nextActions = candidateNextActions(pre, review, readiness, guard);

        AtFaqBatchPublicationPath reviewedPath = review == null ? null : review.resultingPath();
        AtFaqReviewDecisionType decisionType = review == null ? null : review.decisionType();

        return new AtFaqGovernedPublicationCandidate(
                pre.externalId(),
                pre.normalizedQuestion(),
                pre.proposedPublicationPath(),
                decisionType,
                reviewedPath,
                readiness,
                readiness == AtFaqGovernedPublicationReadiness.READY_FOR_FUTURE_PUBLICATION,
                readiness == AtFaqGovernedPublicationReadiness.NEEDS_ASSISTED_REVIEW,
                readiness == AtFaqGovernedPublicationReadiness.NEEDS_MANUAL_REVIEW,
                readiness == AtFaqGovernedPublicationReadiness.BLOCKED,
                readiness == AtFaqGovernedPublicationReadiness.DEFERRED,
                pre.proposedShortAnswer(),
                pre.proposedTechnicalAnswer(),
                pre.proposedTopic(),
                pre.proposedSubtopic(),
                pre.proposedJurisdiction(),
                pre.proposedRiskLevel(),
                pre.proposedSources(),
                pre.proposedLegalReferences(),
                sourceSummaries,
                guard,
                nextActions);
    }

    // --- guards -------------------------------------------------------------

    private AtFaqGovernedPublicationGuardResult evaluateGuards(
            AtFaqPreCuratedBatchItem pre, AtFaqReviewItemResult review) {

        List<String> passed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> blocking = new ArrayList<>();

        // Review presence is the first gate.
        if (review == null) {
            failed.add("review-decision-present");
            blocking.add("Sem decisão de revisão E6 correspondente.");
            return new AtFaqGovernedPublicationGuardResult(false, passed, failed, warnings, blocking);
        }
        passed.add("review-decision-present");

        gate(passed, failed, "review-accepted-for-future-publication", review.acceptedForFuturePublication());
        gate(passed, failed, "review-resulting-path-auto-controlled",
                review.resultingPath() == AtFaqBatchPublicationPath.AUTO_CONTROLLED);
        gate(passed, failed, "pre-curation-path-auto-controlled",
                pre.proposedPublicationPath() == AtFaqBatchPublicationPath.AUTO_CONTROLLED);

        boolean questionOk = isNotBlank(pre.normalizedQuestion());
        gate(passed, failed, "normalized-question-present", questionOk);
        if (!questionOk) {
            blocking.add("Pergunta normalizada em falta.");
        }

        boolean shortOk = isNotBlank(pre.proposedShortAnswer());
        gate(passed, failed, "short-answer-present", shortOk);
        if (!shortOk) {
            blocking.add("Resposta curta em falta.");
        }

        boolean technicalOk = isNotBlank(pre.proposedTechnicalAnswer());
        gate(passed, failed, "technical-answer-present", technicalOk);
        if (!technicalOk) {
            blocking.add("Resposta técnica em falta (nunca inventada).");
        }

        boolean legalOk = !pre.proposedLegalReferences().isEmpty();
        gate(passed, failed, "legal-reference-present", legalOk);
        if (!legalOk) {
            warnings.add("Fundamento legal em falta — exige revisão assistida.");
        }

        boolean hasSource = !pre.proposedSources().isEmpty();
        gate(passed, failed, "at-least-one-source", hasSource);
        if (!hasSource) {
            blocking.add("Sem qualquer fonte proposta.");
        }

        boolean officialSource = pre.proposedSources().stream()
                .anyMatch(AtFaqPreCurationSourceCandidate::official);
        gate(passed, failed, "at-least-one-official-source", officialSource);
        if (!officialSource) {
            blocking.add("Sem fonte oficial.");
        }

        boolean freshnessOk = pre.proposedFreshnessStatus() != FreshnessStatus.OUTDATED;
        gate(passed, failed, "freshness-not-outdated", freshnessOk);
        if (!freshnessOk) {
            blocking.add("Actualidade OUTDATED.");
        }

        boolean noConflict = pre.conflictCandidates().isEmpty();
        gate(passed, failed, "no-conflict-candidates", noConflict);
        if (!noConflict) {
            blocking.add("Conflito por resolver.");
        }

        // A duplicate reference only blocks the redundant COPY (never AUTO_CONTROLLED); the
        // canonical item keeps its downstream copies as an informational cross-reference.
        boolean blockingDuplicate = isBlockingDuplicate(pre);
        gate(passed, failed, "no-blocking-duplicate", !blockingDuplicate);
        if (!pre.duplicateCandidates().isEmpty()) {
            warnings.add("Duplicados relacionados: " + pre.duplicateCandidates() + ".");
        }
        if (blockingDuplicate) {
            blocking.add("Duplicado bloqueante por resolver.");
        }

        boolean noReviewReason = pre.requiredReviewReason() == null;
        gate(passed, failed, "no-required-review-reason", noReviewReason);
        if (!noReviewReason) {
            warnings.add("Motivo de revisão obrigatória herdado da pré-curadoria.");
        }

        boolean noReviewBlocking = review.blockingErrors().isEmpty();
        gate(passed, failed, "no-review-blocking-errors", noReviewBlocking);
        if (!noReviewBlocking) {
            blocking.add("Revisão E6 com erro bloqueante.");
        }

        boolean allPassed = failed.isEmpty();
        return new AtFaqGovernedPublicationGuardResult(allPassed, passed, failed, warnings, blocking);
    }

    private static void gate(List<String> passed, List<String> failed, String name, boolean ok) {
        if (ok) {
            passed.add(name);
        } else {
            failed.add(name);
        }
    }

    // --- readiness ----------------------------------------------------------

    private AtFaqGovernedPublicationReadiness decideReadiness(
            AtFaqPreCuratedBatchItem pre,
            AtFaqReviewItemResult review,
            AtFaqGovernedPublicationGuardResult guard) {

        if (review == null) {
            return AtFaqGovernedPublicationReadiness.DEFERRED;
        }

        boolean blockedByReview = review.rejected()
                || review.resultingPath() == AtFaqBatchPublicationPath.NOT_PUBLISHABLE
                || pre.proposedPublicationPath() == AtFaqBatchPublicationPath.NOT_PUBLISHABLE;
        if (blockedByReview) {
            return AtFaqGovernedPublicationReadiness.BLOCKED;
        }
        if (review.deferred()) {
            return AtFaqGovernedPublicationReadiness.DEFERRED;
        }

        if (guard.passed() && review.acceptedForFuturePublication()) {
            return AtFaqGovernedPublicationReadiness.READY_FOR_FUTURE_PUBLICATION;
        }

        // Not ready: pick the most prudent applicable state (never less prudent than E6).
        if (hasHardBlock(pre, review)) {
            return AtFaqGovernedPublicationReadiness.BLOCKED;
        }
        if (review.resultingPath() == AtFaqBatchPublicationPath.MANUAL_REQUIRED
                || review.decisionType() == AtFaqReviewDecisionType.REQUIRE_MANUAL_REVIEW
                || pre.proposedRiskLevel() == KnowledgeRiskLevel.HIGH
                || pre.proposedRiskLevel() == KnowledgeRiskLevel.CRITICAL) {
            return AtFaqGovernedPublicationReadiness.NEEDS_MANUAL_REVIEW;
        }
        // Everything else that is publishable-but-not-ready needs assisted review.
        return AtFaqGovernedPublicationReadiness.NEEDS_ASSISTED_REVIEW;
    }

    /** Material blockers that forbid any forward movement regardless of the review path. */
    private static boolean hasHardBlock(AtFaqPreCuratedBatchItem pre, AtFaqReviewItemResult review) {
        return !isNotBlank(pre.proposedTechnicalAnswer())
                || !isNotBlank(pre.normalizedQuestion())
                || pre.proposedSources().isEmpty()
                || pre.proposedSources().stream().noneMatch(AtFaqPreCurationSourceCandidate::official)
                || pre.proposedFreshnessStatus() == FreshnessStatus.OUTDATED
                || !pre.conflictCandidates().isEmpty()
                || isBlockingDuplicate(pre)
                || !review.blockingErrors().isEmpty();
    }

    /**
     * A duplicate reference is only a hard block for the redundant <b>copy</b> (which E4/E5 never
     * leave as {@code AUTO_CONTROLLED}); the canonical item keeps its downstream copies purely as
     * an informational cross-reference and stays publishable.
     */
    private static boolean isBlockingDuplicate(AtFaqPreCuratedBatchItem pre) {
        return !pre.duplicateCandidates().isEmpty()
                && pre.proposedPublicationPath() != AtFaqBatchPublicationPath.AUTO_CONTROLLED;
    }

    // --- summaries / actions ------------------------------------------------

    private List<String> summarizeSources(AtFaqPreCuratedBatchItem pre) {
        List<String> summaries = new ArrayList<>();
        for (AtFaqPreCurationSourceCandidate s : pre.proposedSources()) {
            StringBuilder sb = new StringBuilder();
            sb.append(s.type());
            if (isNotBlank(s.title())) {
                sb.append(" — ").append(s.title().strip());
            }
            if (isNotBlank(s.legalReference())) {
                sb.append(" [").append(s.legalReference().strip()).append("]");
            }
            sb.append(s.official() ? " (oficial)" : " (não oficial)");
            summaries.add(sb.toString());
        }
        return summaries;
    }

    private List<String> candidateNextActions(
            AtFaqPreCuratedBatchItem pre,
            AtFaqReviewItemResult review,
            AtFaqGovernedPublicationReadiness readiness,
            AtFaqGovernedPublicationGuardResult guard) {

        List<String> actions = new ArrayList<>();
        switch (readiness) {
            case READY_FOR_FUTURE_PUBLICATION ->
                    actions.add("Pode seguir para publicação governada futura (E8). Ainda não publicado.");
            case NEEDS_MANUAL_REVIEW ->
                    actions.add("Rever manualmente (risco elevado ou decisão manual) antes de publicar.");
            case NEEDS_ASSISTED_REVIEW -> {
                if (pre.proposedLegalReferences().isEmpty()) {
                    actions.add("Confirmar fundamento legal antes de publicar.");
                } else {
                    actions.add("Seguir para revisão assistida antes de publicar.");
                }
            }
            case DEFERRED -> actions.add("Aguardar decisão de revisão / informação adicional.");
            case BLOCKED -> {
                if (!isNotBlank(pre.proposedTechnicalAnswer())) {
                    actions.add("Corrigir resposta técnica antes de publicar.");
                }
                if (!pre.conflictCandidates().isEmpty()) {
                    actions.add("Resolver conflito antes de publicar.");
                }
                if (isBlockingDuplicate(pre)) {
                    actions.add("Rejeitar ou fundir duplicado.");
                }
                if (pre.proposedFreshnessStatus() == FreshnessStatus.OUTDATED) {
                    actions.add("Actualizar fonte desactualizada antes de publicar.");
                }
                if (pre.proposedSources().isEmpty()
                        || pre.proposedSources().stream().noneMatch(AtFaqPreCurationSourceCandidate::official)) {
                    actions.add("Adicionar fonte oficial antes de publicar.");
                }
                if (actions.isEmpty()) {
                    actions.add("Item bloqueado: rever antes de qualquer publicação.");
                }
            }
        }
        return actions;
    }

    private static List<String> buildGlobalNextActions(AtFaqGovernedPublicationTotals t) {
        List<String> actions = new ArrayList<>();
        actions.add(t.readyForFuturePublication()
                + " candidato(s) prontos para publicação governada futura (E8) — ainda não publicados.");
        actions.add(t.needsAssistedReview() + " candidato(s) exigem revisão assistida.");
        actions.add(t.needsManualReview() + " candidato(s) exigem revisão manual.");
        actions.add(t.blocked() + " candidato(s) bloqueados.");
        actions.add(t.deferred() + " candidato(s) diferidos.");
        actions.add("E7 é plano: nada foi publicado nem indexado (published=0, indexed=0).");
        return actions;
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
