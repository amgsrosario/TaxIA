package com.knowledgeflow.ai.grounding;

import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class AnswerGroundingValidator {

    // -------------------------------------------------------------------------
    // Non-monetary sensitive-claim patterns
    // -------------------------------------------------------------------------

    private static final Pattern P_TAX_RATE =
            Pattern.compile("\\b(\\d{1,2}(?:[,.]\\d+)?\\s*%)");

    private static final Pattern P_LEGAL_REF_ARTICLE =
            Pattern.compile("(?i)(artigo\\s+\\d+[.º°]*(?:[\\-A-Z][A-Z]?)?)");

    private static final Pattern P_LEGAL_REF_INSTRUMENT =
            Pattern.compile("(?i)(decreto[\\-\\s]lei|portaria|despacho|circular|ofício[\\-\\s]circulado)" +
                    "\\s+n[.º°]?\\.?\\s*\\d+[\\/\\-]?\\d*");

    private static final Pattern P_DEADLINE =
            Pattern.compile("\\b(\\d+)\\s+(dias?|meses?|anos?)\\b");

    private static final Pattern P_DATE =
            Pattern.compile("(?i)\\b\\d{1,2}\\s+de\\s+" +
                    "(?:janeiro|fevereiro|março|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro)" +
                    "\\s+de\\s+\\d{4}\\b");

    private static final Pattern P_OBLIGATION =
            Pattern.compile("é\\s+obrigatóri[oa][s]?|são\\s+obrigatóri[oa][s]?|" +
                    "deve\\s+liquidar\\s+(?:o\\s+)?[Ii][Vv][Aa]|deve\\s+registar[\\-\\s]?se|" +
                    "deve\\s+declarar\\s+(?:o\\s+)?[Ii][Vv][Aa]|est(?:á|ão)\\s+obrigad[ao]s?\\s+a|" +
                    "é\\s+proibid[ao]|pode\\s+renunciar");

    private static final Pattern P_EXEMPTION =
            Pattern.compile("est(?:á|ão)\\s+isent[ao]s?|está\\s+dispensad[ao]|não\\s+est(?:á|ão)\\s+sujeit[oa]s?");

    private static final Pattern P_DEDUCTIBILITY =
            Pattern.compile("é\\s+dedutível|são\\s+dedutíveis?|não\\s+é\\s+dedutível|não\\s+são\\s+dedutíveis?|" +
                    "tem\\s+direito\\s+à\\s+dedução|têm\\s+direito\\s+à\\s+dedução|pode[m]?\\s+deduzir");

    /** Patterns for all claim types except MONETARY_THRESHOLD (handled separately). */
    private static final List<Map.Entry<SensitiveClaimType, Pattern>> NON_MONETARY_PATTERNS = List.of(
            Map.entry(SensitiveClaimType.TAX_RATE,         P_TAX_RATE),
            Map.entry(SensitiveClaimType.LEGAL_REFERENCE,  P_LEGAL_REF_ARTICLE),
            Map.entry(SensitiveClaimType.LEGAL_REFERENCE,  P_LEGAL_REF_INSTRUMENT),
            Map.entry(SensitiveClaimType.DEADLINE,         P_DEADLINE),
            Map.entry(SensitiveClaimType.DATE,             P_DATE),
            Map.entry(SensitiveClaimType.LEGAL_OBLIGATION, P_OBLIGATION),
            Map.entry(SensitiveClaimType.EXEMPTION,        P_EXEMPTION),
            Map.entry(SensitiveClaimType.DEDUCTIBILITY,    P_DEDUCTIBILITY)
    );

    // -------------------------------------------------------------------------

    private final GroundingProperties props;

    public AnswerGroundingValidator(GroundingProperties props) {
        this.props = props;
    }

    public GroundingValidationResult validate(String answer, List<RetrievedCase> context) {
        if (answer == null || answer.isBlank()) {
            return GroundingValidationResult.noClaimsDetected();
        }

        String contextText = buildContextText(context);
        List<SensitiveClaim> claims = detectClaims(answer, contextText, context);

        if (claims.isEmpty()) {
            return GroundingValidationResult.noClaimsDetected();
        }

        List<SensitiveClaim> unsupported = claims.stream().filter(c -> !c.supported()).toList();

        if (!unsupported.isEmpty() && props.rejectUnsupportedSensitiveClaims()) {
            String reason = "%d afirmação(ões) sensível(eis) sem suporte documental: %s".formatted(
                    unsupported.size(),
                    unsupported.stream().map(SensitiveClaim::text).collect(Collectors.joining(", ")));
            return GroundingValidationResult.rejected(claims, unsupported, reason);
        }

        return GroundingValidationResult.accepted(claims);
    }

    private List<SensitiveClaim> detectClaims(String answer, String contextText, List<RetrievedCase> context) {
        List<SensitiveClaim> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        detectMonetaryClaims(answer, contextText, context, result, seen);

        for (var entry : NON_MONETARY_PATTERNS) {
            SensitiveClaimType type = entry.getKey();
            Matcher m = entry.getValue().matcher(answer);
            while (m.find()) {
                String claimText = m.group().strip();
                if (claimText.length() < 2) continue;
                if (!seen.add(claimText.toLowerCase())) continue;

                boolean supported = isClaimInContext(claimText, contextText);
                List<String> sources = supported
                        ? context.stream()
                                .filter(c -> isClaimInContext(claimText,
                                        c.title() + " " + c.question() + " " + nullSafe(c.content())))
                                .map(RetrievedCase::title)
                                .distinct()
                                .toList()
                        : List.of();

                result.add(new SensitiveClaim(claimText, type, supported, sources));
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Monetary claim detection — uses MonetaryClaimExtractor + MonetaryValueNormalizer
    // -------------------------------------------------------------------------

    private void detectMonetaryClaims(String answer, String contextText,
            List<RetrievedCase> context, List<SensitiveClaim> result, Set<String> seen) {

        // Pre-normalize all monetary values found in the context
        List<NormalizedMonetaryValue> contextValues = MonetaryClaimExtractor.extractAll(contextText)
                .stream()
                .map(MonetaryValueNormalizer::normalize)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        for (String rawMatch : MonetaryClaimExtractor.extractAll(answer)) {
            Optional<NormalizedMonetaryValue> optNmv = MonetaryValueNormalizer.normalize(rawMatch);
            if (optNmv.isEmpty()) continue;

            NormalizedMonetaryValue nmv = optNmv.get();
            // Deduplicate by canonical amount+currency so "650 000 €" and "650.000 €" collapse to one claim
            String dedupeKey = "money:" + nmv.amount().toPlainString() + ":" + nmv.currency();
            if (!seen.add(dedupeKey)) continue;

            boolean supported = contextValues.stream().anyMatch(nmv::sameAs);

            List<String> sources = supported
                    ? context.stream()
                            .filter(c -> monetaryValuePresentIn(nmv,
                                    c.title() + " " + c.question() + " " + nullSafe(c.content())))
                            .map(RetrievedCase::title)
                            .distinct()
                            .toList()
                    : List.of();

            result.add(new SensitiveClaim(rawMatch, SensitiveClaimType.MONETARY_THRESHOLD, supported, sources));
        }
    }

    private boolean monetaryValuePresentIn(NormalizedMonetaryValue nmv, String text) {
        return MonetaryClaimExtractor.extractAll(text)
                .stream()
                .map(MonetaryValueNormalizer::normalize)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .anyMatch(nmv::sameAs);
    }

    // -------------------------------------------------------------------------
    // Generic text comparison
    // -------------------------------------------------------------------------

    private boolean isClaimInContext(String claimText, String contextText) {
        String normClaim = normalize(claimText);
        String normContext = normalize(contextText);
        return (" " + normContext + " ").contains(" " + normClaim + " ");
    }

    private String buildContextText(List<RetrievedCase> cases) {
        return cases.stream()
                .map(c -> c.title() + " " + c.question() + " " + nullSafe(c.content()))
                .collect(Collectors.joining(" "));
    }

    private String normalize(String text) {
        return text.toLowerCase()
                .replaceAll("[.º°]", "")
                .replaceAll("(\\d)\\s+%", "$1%")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String nullSafe(String s) {
        return s != null ? s : "";
    }
}
