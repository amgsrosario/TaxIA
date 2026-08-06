package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.ai.documented.FreshnessStatus;
import com.knowledgeflow.ingestion.atfaq.AtFaqNormalizer;
import com.knowledgeflow.ingestion.atfaq.AtFaqRunMode;
import com.knowledgeflow.ingestion.atfaq.AtFaqRunStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Controlled AT-FAQ batch simulation (Bloco E — E4).
 *
 * <p>"Simular governação antes de mexer no reino publicável." This service runs a small
 * batch of local fixture items entirely in memory — no database, no HTTP, no scraping, no
 * external provider, no embeddings — and produces an {@link AtFaqBatchReport}. It proves the
 * governance flow (normalize → detect duplicates/conflicts → propose a publication path)
 * <b>without ever publishing or indexing</b>. {@code totals.published()} and
 * {@code totals.indexed()} are always {@code 0}.
 *
 * <p>Classification is criteria-based, with no score and no threshold. When several criteria
 * apply, the most restrictive path wins:
 * {@code NOT_PUBLISHABLE > MANUAL_REQUIRED > ASSISTED > AUTO_CONTROLLED}.
 *
 * <p>Deterministic: the same input list yields the same {@code batchId}, the same content
 * hashes and the same item summaries (idempotent at the report level).
 */
@Service
public class AtFaqControlledBatchService {

    private final AtFaqNormalizer normalizer;
    private final Clock clock;

    @Autowired
    public AtFaqControlledBatchService(AtFaqNormalizer normalizer) {
        this(normalizer, Clock.systemUTC());
    }

    /** Test constructor: allows a fixed clock so the whole report is deterministic. */
    AtFaqControlledBatchService(AtFaqNormalizer normalizer, Clock clock) {
        this.normalizer = normalizer;
        this.clock = clock;
    }

    /**
     * Runs the controlled simulation over {@code items} and returns an auditable report.
     * Never publishes and never indexes. Pure in-memory; safe to call repeatedly.
     *
     * @param triggeredBy who/what triggered the run (e.g. a test name)
     * @param items       controlled fixture items (may be empty; null treated as empty)
     */
    public AtFaqBatchReport run(String triggeredBy, List<AtFaqControlledBatchItem> items) {
        Instant startedAt = clock.instant();
        List<AtFaqControlledBatchItem> input = items == null ? List.of() : items;

        // Pre-compute the whitespace-stable question and content hash for every item.
        int n = input.size();
        String[] normalizedQuestions = new String[n];
        String[] contentHashes = new String[n];
        boolean[] structurallyValid = new boolean[n];
        for (int i = 0; i < n; i++) {
            AtFaqControlledBatchItem item = input.get(i);
            normalizedQuestions[i] = normalizer.normalize(item.question());
            contentHashes[i] = normalizer.contentHash(item.question(), item.answer());
            structurallyValid[i] = isNotBlank(item.question()) && isNotBlank(item.answer());
        }

        // Conflict detection is order-independent: a normalized question that appears with more
        // than one distinct content hash means the same question has divergent answers in the lot.
        Map<String, Set<String>> hashesPerQuestion = new HashMap<>();
        for (int i = 0; i < n; i++) {
            if (structurallyValid[i]) {
                hashesPerQuestion
                        .computeIfAbsent(normalizedQuestions[i], k -> new HashSet<>())
                        .add(contentHashes[i]);
            }
        }

        // Duplicate detection is order-sensitive: the first occurrence of a key is canonical,
        // only later occurrences are flagged as duplicates. This lets a clean item stay
        // AUTO_CONTROLLED even when a redundant copy of it appears later in the same lot.
        Set<String> seenHashes = new HashSet<>();
        Set<String> seenExternalIds = new HashSet<>();
        Set<String> seenSourceUrls = new HashSet<>();

        List<AtFaqBatchItemSummary> summaries = new ArrayList<>(n);
        int importedRaw = 0;
        int preCurated = 0;
        int duplicates = 0;
        int conflicts = 0;
        int failed = 0;
        int auto = 0;
        int assisted = 0;
        int manual = 0;
        int notPublishable = 0;

        List<String> batchWarnings = new ArrayList<>();
        List<String> blockingErrors = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            AtFaqControlledBatchItem item = input.get(i);
            String normalizedQuestion = normalizedQuestions[i];
            String contentHash = contentHashes[i];

            boolean valid = structurallyValid[i];
            boolean rawUsable = valid;
            if (!valid) {
                failed++;
                blockingErrors.add("Item " + safeId(item, i)
                        + " sem pergunta ou resposta utilizável (não importável para RAW).");
            } else {
                importedRaw++;
                preCurated++;
            }

            boolean hasTechnical = isNotBlank(item.technicalAnswer());
            boolean hasLegal = isNotBlank(item.legalReference());
            boolean hasSourceUrl = isNotBlank(item.sourceUrl());
            boolean official = item.officialSource();
            KnowledgeRiskLevel risk = item.effectiveRiskLevel();
            FreshnessStatus freshness = item.effectiveFreshness();

            // --- duplicate / conflict detection (within this batch) ---
            boolean exactDuplicate = valid && seenHashes.contains(contentHash);
            boolean possibleDuplicate = valid && !exactDuplicate
                    && ((isNotBlank(item.externalId()) && seenExternalIds.contains(item.externalId()))
                            || (isNotBlank(item.sourceUrl()) && seenSourceUrls.contains(item.sourceUrl())));
            boolean duplicateCandidate = exactDuplicate || possibleDuplicate;
            Set<String> hashesForQuestion = hashesPerQuestion.getOrDefault(normalizedQuestion, Set.of());
            boolean detectedConflict = hashesForQuestion.size() > 1;
            boolean conflictCandidate = valid && (item.conflictMarker() || detectedConflict);

            if (valid) {
                seenHashes.add(contentHash);
                if (isNotBlank(item.externalId())) {
                    seenExternalIds.add(item.externalId());
                }
                if (isNotBlank(item.sourceUrl())) {
                    seenSourceUrls.add(item.sourceUrl());
                }
            }
            if (duplicateCandidate) {
                duplicates++;
            }
            if (conflictCandidate) {
                conflicts++;
            }

            // --- classification (most restrictive wins) ---
            List<String> reasons = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            AtFaqBatchPublicationPath path = null;

            // NOT_PUBLISHABLE conditions
            if (!valid) {
                path = AtFaqBatchPublicationPath.mostRestrictive(path, AtFaqBatchPublicationPath.NOT_PUBLISHABLE);
                reasons.add("Sem pergunta/resposta utilizável.");
            }
            if (valid && !hasTechnical) {
                path = AtFaqBatchPublicationPath.mostRestrictive(path, AtFaqBatchPublicationPath.NOT_PUBLISHABLE);
                reasons.add("Sem resposta técnica curada.");
            }
            if (!hasSourceUrl && !official) {
                path = AtFaqBatchPublicationPath.mostRestrictive(path, AtFaqBatchPublicationPath.NOT_PUBLISHABLE);
                reasons.add("Sem fonte identificável.");
            }
            if (exactDuplicate) {
                path = AtFaqBatchPublicationPath.mostRestrictive(path, AtFaqBatchPublicationPath.NOT_PUBLISHABLE);
                reasons.add("Duplicado exacto de item anterior no lote (sem utilidade adicional).");
            }

            // MANUAL_REQUIRED conditions
            if (risk == KnowledgeRiskLevel.HIGH || risk == KnowledgeRiskLevel.CRITICAL) {
                path = AtFaqBatchPublicationPath.mostRestrictive(path, AtFaqBatchPublicationPath.MANUAL_REQUIRED);
                reasons.add("Risco " + risk + " exige decisão humana.");
            }
            if (conflictCandidate) {
                path = AtFaqBatchPublicationPath.mostRestrictive(path, AtFaqBatchPublicationPath.MANUAL_REQUIRED);
                reasons.add(item.conflictMarker()
                        ? "Conflito assinalado explicitamente na fixture."
                        : "Conflito detectado: mesma pergunta com resposta divergente no lote.");
            }
            if (hasSourceUrl && !official) {
                path = AtFaqBatchPublicationPath.mostRestrictive(path, AtFaqBatchPublicationPath.MANUAL_REQUIRED);
                reasons.add("Fonte não oficial.");
            }
            if (freshness == FreshnessStatus.OUTDATED) {
                path = AtFaqBatchPublicationPath.mostRestrictive(path, AtFaqBatchPublicationPath.MANUAL_REQUIRED);
                reasons.add("Actualidade da fonte OUTDATED.");
            }
            if (item.manualMarker()) {
                path = AtFaqBatchPublicationPath.mostRestrictive(path, AtFaqBatchPublicationPath.MANUAL_REQUIRED);
                reasons.add("Marcado como decisão manual obrigatória na fixture.");
            }

            // ASSISTED conditions
            if (risk == KnowledgeRiskLevel.MEDIUM) {
                path = AtFaqBatchPublicationPath.mostRestrictive(path, AtFaqBatchPublicationPath.ASSISTED);
                reasons.add("Risco MEDIUM: curadoria assistida recomendada.");
            }
            if (valid && hasTechnical && !hasLegal) {
                path = AtFaqBatchPublicationPath.mostRestrictive(path, AtFaqBatchPublicationPath.ASSISTED);
                reasons.add("Sem fundamento legal claro.");
            }
            if (freshness == FreshnessStatus.UNCERTAIN) {
                path = AtFaqBatchPublicationPath.mostRestrictive(path, AtFaqBatchPublicationPath.ASSISTED);
                reasons.add("Actualidade da fonte UNCERTAIN.");
            }
            if (duplicateCandidate && !exactDuplicate) {
                path = AtFaqBatchPublicationPath.mostRestrictive(path, AtFaqBatchPublicationPath.ASSISTED);
                warnings.add("Possível duplicado não bloqueante (rever antes de publicar).");
            }

            // AUTO_CONTROLLED only if nothing more restrictive applied and all positive criteria hold.
            if (path == null) {
                boolean auditableAuto = official
                        && risk == KnowledgeRiskLevel.LOW
                        && hasTechnical
                        && hasLegal
                        && !duplicateCandidate
                        && !conflictCandidate
                        && freshness != FreshnessStatus.OUTDATED
                        && freshness != FreshnessStatus.UNCERTAIN;
                if (auditableAuto) {
                    path = AtFaqBatchPublicationPath.AUTO_CONTROLLED;
                    reasons.add("Fonte oficial, risco LOW, resposta técnica e fundamento legal presentes, "
                            + "sem duplicado/conflito, actualidade aceitável.");
                } else {
                    // Defensive fallback: never silently auto-approve if a positive criterion is missing.
                    path = AtFaqBatchPublicationPath.ASSISTED;
                    reasons.add("Critérios de auto-controlo não totalmente satisfeitos: exige curadoria assistida.");
                }
            }

            switch (path) {
                case AUTO_CONTROLLED -> auto++;
                case ASSISTED -> assisted++;
                case MANUAL_REQUIRED -> manual++;
                case NOT_PUBLISHABLE -> notPublishable++;
            }

            summaries.add(new AtFaqBatchItemSummary(
                    item.externalId(),
                    item.sourceUrl(),
                    normalizedQuestion,
                    contentHash,
                    path,
                    reasons,
                    warnings,
                    duplicateCandidate,
                    conflictCandidate,
                    rawUsable,
                    rawUsable,
                    item.topic(),
                    risk,
                    hasTechnical,
                    hasLegal,
                    official));
        }

        if (duplicates > 0) {
            batchWarnings.add(duplicates + " item(s) com possível duplicação — rever antes de qualquer publicação.");
        }
        if (conflicts > 0) {
            batchWarnings.add(conflicts + " item(s) em conflito — exigem decisão manual.");
        }

        AtFaqBatchReportTotals totals = new AtFaqBatchReportTotals(
                n,
                importedRaw,
                preCurated,
                duplicates,
                conflicts,
                notPublishable,   // rejected == notPublishable in E4
                auto,
                assisted,
                manual,
                notPublishable,
                0,                // published — always 0 in E4
                0,                // indexed — always 0 in E4
                failed);

        List<String> nextActions = buildNextActions(totals);
        // The run itself completes: a non-publishable or conflicting item is a normal governance
        // outcome recorded in the report, not a run failure. AtFaqRunStatus has no warnings state.
        AtFaqRunStatus status = AtFaqRunStatus.COMPLETED;

        String batchId = deterministicBatchId(contentHashes);
        Instant finishedAt = clock.instant();

        return new AtFaqBatchReport(
                batchId,
                AtFaqRunMode.DRY_RUN,
                status,
                startedAt,
                finishedAt,
                triggeredBy,
                "controlled-fixture",
                totals,
                batchWarnings,
                blockingErrors,
                summaries,
                nextActions);
    }

    private static List<String> buildNextActions(AtFaqBatchReportTotals t) {
        List<String> actions = new ArrayList<>();
        if (t.autoControlledCandidates() > 0) {
            actions.add(t.autoControlledCandidates()
                    + " candidato(s) AUTO_CONTROLLED: rever em governação antes de qualquer publicação (E7).");
        }
        if (t.assistedCandidates() > 0) {
            actions.add(t.assistedCandidates()
                    + " item(s) exigem curadoria assistida (E5/E6).");
        }
        if (t.manualRequiredCandidates() > 0) {
            actions.add(t.manualRequiredCandidates()
                    + " item(s) exigem decisão manual / pedido de parecer.");
        }
        if (t.notPublishable() > 0) {
            actions.add(t.notPublishable()
                    + " item(s) não publicáveis: rever fonte/resposta técnica ou rejeitar.");
        }
        actions.add("E4 é simulação controlada: nada foi publicado nem indexado.");
        return actions;
    }

    /** Deterministic id for the batch, derived only from the ordered content hashes. */
    private static String deterministicBatchId(String[] contentHashes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String h : contentHashes) {
                digest.update(h.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            String hex = HexFormat.of().formatHex(digest.digest());
            return "atfaq-batch-" + hex.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String safeId(AtFaqControlledBatchItem item, int index) {
        return isNotBlank(item.externalId()) ? item.externalId() : "#" + index;
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
