package com.knowledgeflow.ingestion.atfaq.batch;

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
 * Governed review of pre-curated AT-FAQ proposals (Bloco E — E6).
 *
 * <p>"Rever não é publicar. É decidir o próximo portão." Given an {@link AtFaqPreCurationResult}
 * (E5) and a list of {@link AtFaqReviewDecision}s, this service decides the <b>next gate</b> for
 * each item. It uses deterministic rules only — no database, no HTTP, no external AI/LLM — and it
 * <b>never publishes, never indexes and never persists</b>. No decision value publishes: real
 * governed publication stays out of scope (E7+).
 *
 * <p>Prudence rule: a review may only keep or increase prudence. The resulting path is never less
 * restrictive than the pre-curation proposal. An attempt to relax prudence (notably promoting a
 * non-{@code AUTO_CONTROLLED} item to {@code AUTO_CONTROLLED}) is refused with a blocking error and
 * the item keeps its more-restrictive path.
 */
@Service
public class AtFaqReviewService {

    private final Clock clock;

    @Autowired
    public AtFaqReviewService() {
        this(Clock.systemUTC());
    }

    /** Test constructor: fixed clock makes the review timestamp deterministic. */
    AtFaqReviewService(Clock clock) {
        this.clock = clock;
    }

    /**
     * Applies the given decisions to a pre-curation result, producing a governed review result.
     * Items are processed in pre-curation order; decisions are matched by {@code externalId}.
     */
    public AtFaqReviewResult review(
            AtFaqPreCurationResult preCurationResult,
            List<AtFaqReviewDecision> decisions,
            String reviewedBy) {

        Instant reviewedAt = clock.instant();
        String who = isNotBlank(reviewedBy) ? reviewedBy.strip() : "system";

        List<AtFaqPreCuratedBatchItem> preItems =
                preCurationResult == null ? List.of() : preCurationResult.items();
        List<AtFaqReviewDecision> input = decisions == null ? List.of() : decisions;

        List<String> globalWarnings = new ArrayList<>();

        // --- collapse decisions by externalId, most-restrictive-wins on duplicates ---
        Set<String> knownIds = new LinkedHashSet<>();
        for (AtFaqPreCuratedBatchItem pre : preItems) {
            knownIds.add(pre.externalId());
        }
        Map<String, AtFaqReviewDecision> decisionById = new LinkedHashMap<>();
        for (AtFaqReviewDecision d : input) {
            if (d == null || d.externalId() == null) {
                globalWarnings.add("Decisão sem externalId ignorada.");
                continue;
            }
            String id = d.externalId();
            if (!knownIds.contains(id)) {
                globalWarnings.add("Decisão para externalId inexistente ignorada: " + id + ".");
                continue;
            }
            AtFaqReviewDecision existing = decisionById.get(id);
            if (existing == null) {
                decisionById.put(id, d);
            } else {
                AtFaqReviewDecision stricter = moreRestrictive(existing, d);
                decisionById.put(id, stricter);
                globalWarnings.add("Decisão duplicada para " + id
                        + ": aplicada a mais restritiva (" + stricter.decisionType() + ").");
            }
        }

        // --- per-item review ---
        List<AtFaqReviewItemResult> itemResults = new ArrayList<>(preItems.size());
        int decisionsProvided = 0;
        int acceptedAuto = 0;
        int assisted = 0;
        int manual = 0;
        int rejected = 0;
        int deferred = 0;
        int missing = 0;
        int blocked = 0;
        int warningsTotal = 0;

        for (AtFaqPreCuratedBatchItem pre : preItems) {
            AtFaqReviewDecision decision = decisionById.get(pre.externalId());
            AtFaqReviewItemResult r = reviewItem(pre, decision);
            itemResults.add(r);

            if (decision != null) {
                decisionsProvided++;
            } else {
                missing++;
            }
            if (r.acceptedForFuturePublication()) {
                acceptedAuto++;
            }
            if (r.decisionType() == AtFaqReviewDecisionType.SEND_TO_ASSISTED_REVIEW
                    && r.blockingErrors().isEmpty()) {
                assisted++;
            }
            if (r.decisionType() == AtFaqReviewDecisionType.REQUIRE_MANUAL_REVIEW
                    && r.blockingErrors().isEmpty()) {
                manual++;
            }
            if (r.rejected()) {
                rejected++;
            }
            if (r.deferred()) {
                deferred++;
            }
            if (!r.blockingErrors().isEmpty()) {
                blocked++;
            }
            warningsTotal += r.warnings().size();
        }

        AtFaqReviewTotals totals = new AtFaqReviewTotals(
                preItems.size(), decisionsProvided, acceptedAuto, assisted, manual,
                rejected, deferred, missing, blocked, warningsTotal,
                0 /* published */, 0 /* indexed */);

        if (blocked > 0) {
            globalWarnings.add(blocked + " item(s) com erro bloqueante de revisão (prudência não relaxada).");
        }

        List<String> nextActions = buildNextActions(totals);
        String batchId = preCurationResult == null ? null : preCurationResult.batchId();

        return new AtFaqReviewResult(
                batchId, reviewedAt, who, totals, itemResults, globalWarnings, List.of(), nextActions);
    }

    private AtFaqReviewItemResult reviewItem(AtFaqPreCuratedBatchItem pre, AtFaqReviewDecision decision) {
        List<String> reasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> blockingErrors = new ArrayList<>();

        AtFaqBatchPublicationPath proposedPath = pre.proposedPublicationPath() == null
                ? AtFaqBatchPublicationPath.ASSISTED
                : pre.proposedPublicationPath();

        boolean decisionMissing = decision == null;
        AtFaqReviewDecisionType decisionType =
                decisionMissing ? AtFaqReviewDecisionType.DEFER : decision.decisionType();
        if (decisionType == null) {
            decisionType = AtFaqReviewDecisionType.DEFER;
            warnings.add("Decisão sem tipo — diferida por defeito.");
        }

        // --- justification checks ---
        if (decisionMissing) {
            reasons.add("Decisão em falta — diferida por defeito.");
        } else {
            boolean reasonBlank = !isNotBlank(decision.reason());
            if (decisionType == AtFaqReviewDecisionType.DEFER) {
                if (reasonBlank && decision.notes().isEmpty()) {
                    warnings.add("DEFER sem justificação nem notas.");
                }
            } else if (reasonBlank) {
                warnings.add("Decisão forte (" + decisionType + ") sem justificação (reason vazio).");
            }
            if (isNotBlank(decision.reason())) {
                reasons.add(decision.reason().strip());
            }
        }

        // --- resulting path: prudence may only stay or increase ---
        AtFaqBatchPublicationPath decisionPath = pathFor(decisionType, proposedPath);
        AtFaqBatchPublicationPath resultingPath =
                AtFaqBatchPublicationPath.mostRestrictive(proposedPath, decisionPath);

        // --- validate the decision against the proposed path (no improper relaxation) ---
        if (decisionType == AtFaqReviewDecisionType.ACCEPT_AUTO_CONTROLLED_CANDIDATE) {
            if (proposedPath != AtFaqBatchPublicationPath.AUTO_CONTROLLED) {
                blockingErrors.add("Não é permitido promover um item " + proposedPath
                        + " para AUTO_CONTROLLED.");
            } else if (pre.requiredReviewReason() != null) {
                blockingErrors.add("Item AUTO_CONTROLLED com motivo de revisão obrigatória ("
                        + pre.requiredReviewReason() + ") não pode ser aceite como candidato automático.");
            }
        } else if (isRelaxationAttempt(decisionType, decisionPath, proposedPath)) {
            // SEND_TO_ASSISTED / REQUIRE_MANUAL that would lower prudence.
            if (proposedPath == AtFaqBatchPublicationPath.MANUAL_REQUIRED) {
                blockingErrors.add("Item MANUAL_REQUIRED não pode ser encaminhado para "
                        + decisionType + " (prudência não pode ser relaxada).");
            } else if (proposedPath == AtFaqBatchPublicationPath.NOT_PUBLISHABLE) {
                warnings.add("Item NOT_PUBLISHABLE mantido não publicável; " + decisionType
                        + " só válido como reanálise futura.");
            }
        }

        boolean itemBlocked = !blockingErrors.isEmpty();

        // --- outcome flags ---
        boolean accepted = decisionType == AtFaqReviewDecisionType.ACCEPT_AUTO_CONTROLLED_CANDIDATE
                && proposedPath == AtFaqBatchPublicationPath.AUTO_CONTROLLED
                && pre.requiredReviewReason() == null
                && !itemBlocked
                && resultingPath == AtFaqBatchPublicationPath.AUTO_CONTROLLED;

        boolean rejected = decisionType == AtFaqReviewDecisionType.REJECT
                || resultingPath == AtFaqBatchPublicationPath.NOT_PUBLISHABLE;

        boolean deferred = decisionMissing || decisionType == AtFaqReviewDecisionType.DEFER;

        boolean requiresHumanReview = !rejected && !accepted
                && (decisionType == AtFaqReviewDecisionType.SEND_TO_ASSISTED_REVIEW
                        || decisionType == AtFaqReviewDecisionType.REQUIRE_MANUAL_REVIEW
                        || resultingPath == AtFaqBatchPublicationPath.ASSISTED
                        || resultingPath == AtFaqBatchPublicationPath.MANUAL_REQUIRED
                        || pre.requiredReviewReason() != null);

        // --- explanatory reasons ---
        if (accepted) {
            reasons.add("Aceite como candidato a publicação governada futura (não publicado, não indexado).");
        }
        if (resultingPath != proposedPath) {
            reasons.add("Prudência ajustada: " + proposedPath + " → " + resultingPath + ".");
        }
        if (pre.requiredReviewReason() != null) {
            reasons.add("Motivo de revisão herdado da pré-curadoria: " + pre.requiredReviewReason());
        }
        if (deferred && !decisionMissing) {
            reasons.add("Decisão adiada por falta de elementos.");
        }

        return new AtFaqReviewItemResult(
                pre.externalId(),
                pre.normalizedQuestion(),
                proposedPath,
                decisionType,
                resultingPath,
                accepted,
                requiresHumanReview,
                rejected,
                deferred,
                reasons,
                warnings,
                blockingErrors);
    }

    /**
     * Maps a decision type to the future path it targets. DEFER keeps the proposed path but never
     * below ASSISTED (a deferred item is never treated as auto-controllable while pending).
     */
    private static AtFaqBatchPublicationPath pathFor(
            AtFaqReviewDecisionType decisionType, AtFaqBatchPublicationPath proposedPath) {
        return switch (decisionType) {
            case ACCEPT_AUTO_CONTROLLED_CANDIDATE -> AtFaqBatchPublicationPath.AUTO_CONTROLLED;
            case SEND_TO_ASSISTED_REVIEW -> AtFaqBatchPublicationPath.ASSISTED;
            case REQUIRE_MANUAL_REVIEW -> AtFaqBatchPublicationPath.MANUAL_REQUIRED;
            case REJECT -> AtFaqBatchPublicationPath.NOT_PUBLISHABLE;
            case DEFER -> AtFaqBatchPublicationPath.mostRestrictive(
                    proposedPath, AtFaqBatchPublicationPath.ASSISTED);
        };
    }

    /** True when a gate decision (assisted/manual) would lower prudence below the proposed path. */
    private static boolean isRelaxationAttempt(
            AtFaqReviewDecisionType decisionType,
            AtFaqBatchPublicationPath decisionPath,
            AtFaqBatchPublicationPath proposedPath) {
        if (decisionType != AtFaqReviewDecisionType.SEND_TO_ASSISTED_REVIEW
                && decisionType != AtFaqReviewDecisionType.REQUIRE_MANUAL_REVIEW) {
            return false;
        }
        return decisionPath.restrictiveness() < proposedPath.restrictiveness();
    }

    /** Returns the decision whose target path is stricter (ties keep the first). */
    private static AtFaqReviewDecision moreRestrictive(AtFaqReviewDecision a, AtFaqReviewDecision b) {
        int ra = rank(a.decisionType());
        int rb = rank(b.decisionType());
        return rb > ra ? b : a;
    }

    /** Prudence rank for collapsing duplicate decisions (higher = more prudent). */
    private static int rank(AtFaqReviewDecisionType type) {
        if (type == null) {
            return 1; // treat as DEFER
        }
        return switch (type) {
            case ACCEPT_AUTO_CONTROLLED_CANDIDATE -> 0;
            case DEFER -> 1;
            case SEND_TO_ASSISTED_REVIEW -> 2;
            case REQUIRE_MANUAL_REVIEW -> 3;
            case REJECT -> 4;
        };
    }

    private static List<String> buildNextActions(AtFaqReviewTotals t) {
        List<String> actions = new ArrayList<>();
        actions.add(t.acceptedAutoControlledCandidates()
                + " item(s) podem seguir para publicação governada futura (E7) — ainda não publicados.");
        actions.add(t.sentToAssistedReview() + " item(s) para revisão assistida.");
        actions.add(t.manualReviewRequired() + " item(s) para revisão manual obrigatória.");
        actions.add(t.rejected() + " item(s) rejeitados.");
        actions.add(t.deferred() + " item(s) diferidos (decisão adiada ou em falta).");
        actions.add("E6 é revisão: nada foi publicado nem indexado (published=0, indexed=0).");
        return actions;
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
