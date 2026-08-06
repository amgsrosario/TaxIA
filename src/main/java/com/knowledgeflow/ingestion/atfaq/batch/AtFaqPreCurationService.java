package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.ai.documented.AuthorityLevel;
import com.knowledgeflow.ai.documented.FreshnessStatus;
import com.knowledgeflow.ai.documented.SourceDiversity;
import com.knowledgeflow.ai.documented.SourceQuality;
import com.knowledgeflow.ai.documented.SourceRole;
import com.knowledgeflow.ingestion.atfaq.AtFaqNormalizer;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Controlled, deterministic pre-curation of an AT-FAQ batch (Bloco E — E5).
 *
 * <p>"A pré-curadoria prepara o caso. Não o autoriza a responder." Given the E4 controlled
 * batch, this service produces an {@link AtFaqPreCurationResult}: structured curation
 * <b>proposals</b> for each item (short/technical answer, topic, risk, sources, legal
 * references, freshness, publication path). It uses deterministic rules only — no database,
 * no HTTP, no scraping, no external provider, no LLM — and never publishes, indexes or creates
 * embeddings. Proposals are never authoritative; a technical answer is never invented.
 *
 * <p>The proposed publication path inherits the E4 classification and may only become
 * <b>more</b> restrictive (prudence up, never down).
 */
@Service
public class AtFaqPreCurationService {

    /** Detects simple legal references: fiscal codes and article citations. */
    private static final Pattern LEGAL_PATTERN = Pattern.compile(
            "(?:artigo|art\\.)\\s*\\d+\\.?[ºo]?(?:\\s*d[eo]\\s+(?:C[A-Z]{3,5}|[A-Za-zÀ-ÿ]+))?"
                    + "|\\bC(?:IVA|IRS|IRC|IMI|IMT|IEC|IS)\\b"
                    + "|\\bLGT\\b|\\bCPPT\\b",
            Pattern.CASE_INSENSITIVE);

    private static final int SHORT_ANSWER_MAX = 240;

    private final AtFaqControlledBatchService controlledBatchService;
    private final AtFaqNormalizer normalizer;
    private final Clock clock;

    @Autowired
    public AtFaqPreCurationService(AtFaqControlledBatchService controlledBatchService, AtFaqNormalizer normalizer) {
        this(controlledBatchService, normalizer, Clock.systemUTC());
    }

    /** Test constructor: fixed clock makes the whole result deterministic. */
    AtFaqPreCurationService(AtFaqControlledBatchService controlledBatchService, AtFaqNormalizer normalizer, Clock clock) {
        this.controlledBatchService = controlledBatchService;
        this.normalizer = normalizer;
        this.clock = clock;
    }

    /** Convenience: runs the E4 batch and pre-curates it in one call. */
    public AtFaqPreCurationResult preCurate(List<AtFaqControlledBatchItem> items) {
        List<AtFaqControlledBatchItem> input = items == null ? List.of() : items;
        AtFaqBatchReport report = controlledBatchService.run("e5-pre-curation", input);
        return preCurate(report, input);
    }

    /**
     * Pre-curates a batch, reusing the E4 report for classification, content hashes and batchId.
     * {@code batchReport.itemSummaries()} must correspond positionally to {@code originalItems}
     * (as produced by {@link AtFaqControlledBatchService#run}).
     */
    public AtFaqPreCurationResult preCurate(AtFaqBatchReport batchReport, List<AtFaqControlledBatchItem> originalItems) {
        Instant generatedAt = clock.instant();
        List<AtFaqControlledBatchItem> input = originalItems == null ? List.of() : originalItems;
        List<AtFaqBatchItemSummary> summaries = batchReport.itemSummaries();

        // Cross-item indexes for duplicate/conflict candidate listing (valid items only).
        Map<String, List<String>> idsByHash = new HashMap<>();
        Map<String, List<String>> idsByQuestion = new HashMap<>();
        for (AtFaqBatchItemSummary s : summaries) {
            if (s.importedRaw()) {
                idsByHash.computeIfAbsent(s.contentHash(), k -> new ArrayList<>()).add(s.externalId());
                idsByQuestion.computeIfAbsent(s.normalizedQuestion(), k -> new ArrayList<>()).add(s.externalId());
            }
        }

        List<AtFaqPreCuratedBatchItem> items = new ArrayList<>(input.size());
        int preCurated = 0;
        int withTechnical = 0;
        int withLegal = 0;
        int withOfficial = 0;
        int auto = 0;
        int assisted = 0;
        int manual = 0;
        int notPublishable = 0;
        int requiringReview = 0;
        int warningsTotal = 0;
        int conflicts = 0;
        int duplicates = 0;

        for (int i = 0; i < input.size(); i++) {
            AtFaqControlledBatchItem item = input.get(i);
            AtFaqBatchItemSummary summary = summaries.get(i);
            AtFaqPreCuratedBatchItem pre = preCurateItem(item, summary, idsByHash, idsByQuestion);
            items.add(pre);

            if (summary.importedRaw()) {
                preCurated++;
            }
            if (pre.proposedTechnicalAnswer() != null && !pre.proposedTechnicalAnswer().isBlank()) {
                withTechnical++;
            }
            if (!pre.proposedLegalReferences().isEmpty()) {
                withLegal++;
            }
            if (summary.officialSource()) {
                withOfficial++;
            }
            switch (pre.proposedPublicationPath()) {
                case AUTO_CONTROLLED -> auto++;
                case ASSISTED -> assisted++;
                case MANUAL_REQUIRED -> manual++;
                case NOT_PUBLISHABLE -> notPublishable++;
            }
            if (pre.requiredReviewReason() != null) {
                requiringReview++;
            }
            warningsTotal += pre.warnings().size();
            if (!pre.conflictCandidates().isEmpty()) {
                conflicts++;
            }
            if (!pre.duplicateCandidates().isEmpty()) {
                duplicates++;
            }
        }

        AtFaqPreCurationTotals totals = new AtFaqPreCurationTotals(
                input.size(), preCurated, withTechnical, withLegal, withOfficial,
                auto, assisted, manual, notPublishable, requiringReview,
                warningsTotal, conflicts, duplicates);

        List<String> globalWarnings = new ArrayList<>();
        if (notPublishable > 0) {
            globalWarnings.add(notPublishable + " item(s) não publicáveis nesta pré-curadoria.");
        }
        if (requiringReview > 0) {
            globalWarnings.add(requiringReview + " item(s) exigem revisão humana antes de qualquer publicação.");
        }

        List<String> nextActions = buildNextActions(totals);

        return new AtFaqPreCurationResult(
                batchReport.batchId(), generatedAt, totals, items, globalWarnings, nextActions);
    }

    private AtFaqPreCuratedBatchItem preCurateItem(
            AtFaqControlledBatchItem item,
            AtFaqBatchItemSummary summary,
            Map<String, List<String>> idsByHash,
            Map<String, List<String>> idsByQuestion) {

        List<String> warnings = new ArrayList<>();
        List<String> confidenceSignals = new ArrayList<>();

        String normalizedQuestion = summary.normalizedQuestion();

        // --- short answer: deterministic summary; never invented ---
        String proposedShortAnswer = summarize(firstNonBlank(item.answer(), item.technicalAnswer()));
        if (proposedShortAnswer == null) {
            warnings.add("Sem resposta base para produzir resposta curta.");
        }

        // --- technical answer: verbatim if present; never invented ---
        String proposedTechnicalAnswer = isNotBlank(item.technicalAnswer()) ? item.technicalAnswer().strip() : null;
        boolean hasTechnical = proposedTechnicalAnswer != null;
        if (!hasTechnical) {
            warnings.add("Sem resposta técnica curada — carece de redacção humana (não inventada).");
        } else {
            confidenceSignals.add("Resposta técnica presente.");
        }

        // --- topic / subtopic / jurisdiction ---
        KnowledgeTopic proposedTopic = inferTopic(item.topic(), item.question());
        if (proposedTopic == KnowledgeTopic.OUTROS) {
            warnings.add("Tema não inferido com segurança — classificado como OUTROS.");
        }
        String proposedJurisdiction = "PT";

        // --- risk: explicit value, never lowered ---
        KnowledgeRiskLevel proposedRiskLevel = item.effectiveRiskLevel();
        if (item.riskLevel() != null) {
            confidenceSignals.add("Risco explícito: " + item.riskLevel() + ".");
        }

        // --- legal references ---
        List<String> proposedLegalReferences = detectLegalReferences(item);
        boolean hasLegal = !proposedLegalReferences.isEmpty();
        if (hasLegal) {
            confidenceSignals.add("Referência legal presente.");
        } else {
            warnings.add("Sem referência legal detectada.");
        }

        // --- freshness: UNCERTAIN unless the fixture carries an explicit marker ---
        FreshnessStatus proposedFreshness = item.freshnessStatus() != null
                ? item.freshnessStatus()
                : FreshnessStatus.UNCERTAIN;

        // --- duplicate / conflict candidate ids ---
        boolean official = item.officialSource();
        if (official) {
            confidenceSignals.add("Fonte oficial presente.");
        }
        List<String> duplicateCandidates = otherIdsSharing(idsByHash, summary.contentHash(), summary.externalId());
        List<String> conflictCandidates = new ArrayList<>();
        if (item.conflictMarker()) {
            conflictCandidates.add("explicit-conflict-marker");
        }
        for (String other : otherIdsSharing(idsByQuestion, summary.normalizedQuestion(), summary.externalId())) {
            // same question but a different content hash means divergent answers
            if (!duplicateCandidates.contains(other)) {
                conflictCandidates.add("divergent-answer:" + other);
            }
        }
        if (!duplicateCandidates.isEmpty() || summary.duplicateCandidate()) {
            confidenceSignals.add("Duplicado detectado.");
        }
        if (!conflictCandidates.isEmpty() || summary.conflictCandidate()) {
            confidenceSignals.add("Conflito detectado.");
        }

        // --- sources ---
        SourceDiversity diversity = resolveDiversity(item, summary, hasLegal);
        if (diversity == SourceDiversity.MATERIAL_DIVERSITY) {
            confidenceSignals.add("Diversidade FAQ+legislação.");
        }
        List<AtFaqPreCurationSourceCandidate> sources =
                buildSources(item, proposedLegalReferences, proposedFreshness, diversity, hasLegal);

        // --- publication path: inherit E4, escalate for prudence, never relax ---
        AtFaqBatchPublicationPath path = escalatePath(
                summary.proposedPath(), hasTechnical, hasLegal, proposedFreshness,
                summary.conflictCandidate() || !conflictCandidates.isEmpty(), summary.importedRaw());

        boolean eligibleAuto = path == AtFaqBatchPublicationPath.AUTO_CONTROLLED;
        String requiredReviewReason = eligibleAuto ? null : mainReviewReason(
                summary.importedRaw(), hasTechnical, official, summary.conflictCandidate() || !conflictCandidates.isEmpty(),
                !duplicateCandidates.isEmpty(), proposedRiskLevel, proposedFreshness, hasLegal);

        return new AtFaqPreCuratedBatchItem(
                item.externalId(),
                item.sourceUrl(),
                normalizedQuestion,
                proposedShortAnswer,
                proposedTechnicalAnswer,
                proposedTopic,
                null, // proposedSubtopic — no explicit marker in the controlled item
                proposedJurisdiction,
                proposedRiskLevel,
                sources,
                proposedLegalReferences,
                proposedFreshness,
                path,
                duplicateCandidates,
                conflictCandidates,
                confidenceSignals,
                warnings,
                requiredReviewReason,
                eligibleAuto);
    }

    // --- source construction ------------------------------------------------

    private List<AtFaqPreCurationSourceCandidate> buildSources(
            AtFaqControlledBatchItem item,
            List<String> legalReferences,
            FreshnessStatus freshness,
            SourceDiversity diversity,
            boolean hasLegal) {

        List<AtFaqPreCurationSourceCandidate> sources = new ArrayList<>();
        boolean official = item.officialSource();

        // The FAQ source is primary only when there is no stronger legislation candidate.
        boolean faqPrimary = !hasLegal;
        if (isNotBlank(item.sourceUrl()) || isNotBlank(item.sourceTitle())) {
            List<String> faqWarnings = new ArrayList<>();
            if (!official) {
                faqWarnings.add("Fonte FAQ não marcada como oficial.");
            }
            sources.add(new AtFaqPreCurationSourceCandidate(
                    KnowledgeSourceType.OFFICIAL_FAQ,
                    firstNonBlank(item.sourceTitle(), "FAQ AT"),
                    item.sourceUrl(),
                    null,
                    official ? AuthorityLevel.OFFICIAL_FAQ : AuthorityLevel.EXTERNAL_NON_OFFICIAL,
                    official ? SourceQuality.ADEQUATE : SourceQuality.WEAK,
                    faqPrimary ? SourceRole.PRIMARY : SourceRole.COMPLEMENTARY,
                    normalizeUrlCore(item.sourceUrl()),
                    diversity,
                    freshness,
                    official,
                    faqPrimary,
                    faqWarnings));
        }

        // A legislation candidate for each detected legal reference.
        for (String legalRef : legalReferences) {
            sources.add(new AtFaqPreCurationSourceCandidate(
                    KnowledgeSourceType.LEGISLATION,
                    legalRef,
                    null,
                    legalRef,
                    AuthorityLevel.LEGAL,
                    SourceQuality.STRONG,
                    SourceRole.PRIMARY,
                    normalizeLegalCore(legalRef),
                    diversity,
                    freshness,
                    true,
                    true,
                    List.of()));
        }
        return sources;
    }

    private static SourceDiversity resolveDiversity(
            AtFaqControlledBatchItem item, AtFaqBatchItemSummary summary, boolean hasLegal) {
        if (summary.duplicateCandidate()) {
            return SourceDiversity.SAME_CORE;
        }
        boolean hasFaq = isNotBlank(item.sourceUrl()) || isNotBlank(item.sourceTitle());
        if (hasFaq && hasLegal) {
            return SourceDiversity.MATERIAL_DIVERSITY;
        }
        return SourceDiversity.MIXED_OR_UNCLEAR;
    }

    // --- publication path escalation ----------------------------------------

    private static AtFaqBatchPublicationPath escalatePath(
            AtFaqBatchPublicationPath e4Path,
            boolean hasTechnical,
            boolean hasLegal,
            FreshnessStatus freshness,
            boolean conflict,
            boolean importedRaw) {

        AtFaqBatchPublicationPath path = e4Path == null ? AtFaqBatchPublicationPath.ASSISTED : e4Path;

        if (!importedRaw || !hasTechnical) {
            path = AtFaqBatchPublicationPath.mostRestrictive(path, AtFaqBatchPublicationPath.NOT_PUBLISHABLE);
        }
        if (freshness == FreshnessStatus.OUTDATED) {
            path = AtFaqBatchPublicationPath.mostRestrictive(path, AtFaqBatchPublicationPath.MANUAL_REQUIRED);
        }
        if (conflict) {
            path = AtFaqBatchPublicationPath.mostRestrictive(path, AtFaqBatchPublicationPath.MANUAL_REQUIRED);
        }
        // Missing legal foundation can only downgrade an otherwise-auto candidate to assisted.
        if (!hasLegal && path == AtFaqBatchPublicationPath.AUTO_CONTROLLED) {
            path = AtFaqBatchPublicationPath.ASSISTED;
        }
        return path;
    }

    private static String mainReviewReason(
            boolean importedRaw, boolean hasTechnical, boolean official, boolean conflict,
            boolean duplicate, KnowledgeRiskLevel risk, FreshnessStatus freshness, boolean hasLegal) {
        if (!importedRaw) {
            return "Item sem pergunta/resposta utilizável.";
        }
        if (!hasTechnical) {
            return "Falta resposta técnica curada.";
        }
        if (conflict) {
            return "Conflito com conhecimento existente.";
        }
        if (duplicate) {
            return "Possível duplicação no lote.";
        }
        if (risk == KnowledgeRiskLevel.HIGH || risk == KnowledgeRiskLevel.CRITICAL) {
            return "Risco " + risk + " exige decisão humana.";
        }
        if (freshness == FreshnessStatus.OUTDATED) {
            return "Actualidade da fonte OUTDATED.";
        }
        if (!hasLegal) {
            return "Falta fundamento legal.";
        }
        if (risk == KnowledgeRiskLevel.MEDIUM) {
            return "Risco MEDIUM: curadoria assistida recomendada.";
        }
        if (!official) {
            return "Fonte não oficial.";
        }
        if (freshness == FreshnessStatus.UNCERTAIN) {
            return "Actualidade da fonte incerta.";
        }
        return "Revisão humana recomendada.";
    }

    // --- helpers ------------------------------------------------------------

    private List<String> detectLegalReferences(AtFaqControlledBatchItem item) {
        Set<String> refs = new LinkedHashSet<>();
        if (isNotBlank(item.legalReference())) {
            refs.add(item.legalReference().strip());
            return new ArrayList<>(refs);
        }
        String text = normalizer.normalize(
                nullToEmpty(item.question()) + "\n" + nullToEmpty(item.answer())
                        + "\n" + nullToEmpty(item.technicalAnswer()));
        Matcher m = LEGAL_PATTERN.matcher(text);
        while (m.find()) {
            refs.add(m.group().strip());
        }
        return new ArrayList<>(refs);
    }

    private KnowledgeTopic inferTopic(String topic, String question) {
        String haystack = (nullToEmpty(topic) + " " + nullToEmpty(question)).toUpperCase();
        if (haystack.contains("IVA")) return KnowledgeTopic.IVA;
        if (haystack.contains("IRC")) return KnowledgeTopic.IRC;
        if (haystack.contains("IRS")) return KnowledgeTopic.IRS;
        if (haystack.contains("SEGURAN")) return KnowledgeTopic.SEGURANCA_SOCIAL;
        if (haystack.contains("TRABALH")) return KnowledgeTopic.TRABALHO;
        if (haystack.contains("CONTAB")) return KnowledgeTopic.CONTABILIDADE;
        if (haystack.contains("PROCEDIMENT")) return KnowledgeTopic.PROCEDIMENTO_TRIBUTARIO;
        if (haystack.contains("FATURA") || haystack.contains("FACTURA")) return KnowledgeTopic.FATURACAO;
        return KnowledgeTopic.OUTROS;
    }

    private String summarize(String base) {
        if (!isNotBlank(base)) {
            return null;
        }
        String norm = normalizer.normalize(base).replace('\n', ' ').strip();
        if (norm.length() <= SHORT_ANSWER_MAX) {
            return norm;
        }
        // Prefer cutting at the first sentence end within the limit.
        int sentenceEnd = -1;
        for (int i = 0; i < Math.min(norm.length(), SHORT_ANSWER_MAX); i++) {
            char c = norm.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                sentenceEnd = i;
            }
        }
        if (sentenceEnd >= 40) {
            return norm.substring(0, sentenceEnd + 1);
        }
        int cut = norm.lastIndexOf(' ', SHORT_ANSWER_MAX);
        if (cut < 40) {
            cut = SHORT_ANSWER_MAX;
        }
        return norm.substring(0, cut).strip() + "…";
    }

    private static List<String> otherIdsSharing(Map<String, List<String>> index, String key, String selfId) {
        List<String> all = index.getOrDefault(key, List.of());
        if (all.size() <= 1) {
            return List.of();
        }
        List<String> others = new ArrayList<>();
        for (String id : all) {
            if (!id.equals(selfId)) {
                others.add(id);
            }
        }
        return others;
    }

    private static String normalizeUrlCore(String url) {
        if (!isNotBlank(url)) {
            return "";
        }
        String core = url.trim().toLowerCase();
        int scheme = core.indexOf("://");
        if (scheme >= 0) {
            core = core.substring(scheme + 3);
        } else if (core.contains(":")) {
            core = core.substring(core.indexOf(':') + 1);
        }
        int query = core.indexOf('?');
        if (query >= 0) {
            core = core.substring(0, query);
        }
        int frag = core.indexOf('#');
        if (frag >= 0) {
            core = core.substring(0, frag);
        }
        return core.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private static String normalizeLegalCore(String legalRef) {
        return legalRef == null ? "" : legalRef.toLowerCase().replaceAll("[^a-z0-9º]+", " ").strip();
    }

    private static List<String> buildNextActions(AtFaqPreCurationTotals t) {
        List<String> actions = new ArrayList<>();
        if (t.autoControlledCandidates() > 0) {
            actions.add(t.autoControlledCandidates()
                    + " candidato(s) AUTO_CONTROLLED: rever em governação antes de publicar (E7).");
        }
        if (t.assistedCandidates() > 0) {
            actions.add(t.assistedCandidates() + " item(s) para curadoria assistida (E6).");
        }
        if (t.manualRequiredCandidates() > 0) {
            actions.add(t.manualRequiredCandidates() + " item(s) para decisão manual / parecer.");
        }
        if (t.notPublishable() > 0) {
            actions.add(t.notPublishable() + " item(s) não publicáveis: rever fonte/resposta técnica.");
        }
        actions.add("E5 é pré-curadoria: nada foi publicado nem indexado; a proposta não é decisão final.");
        return actions;
    }

    private static String firstNonBlank(String a, String b) {
        if (isNotBlank(a)) return a;
        if (isNotBlank(b)) return b;
        return null;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
