package com.knowledgeflow.ai.grounding;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds all monetary expressions in a piece of text.
 * Only matches text that contains an explicit currency indicator (€, EUR, euros).
 * Plain numbers without a currency symbol are never returned.
 */
public class MonetaryClaimExtractor {

    /**
     * Numeric core: grouped thousands (1-3 digits + space/dot groups of 3) or plain 4+ digits.
     * In character class [. ] the dot is literal (not "any char").
     * Optional decimal: comma or dot followed by 1–2 digits.
     */
    private static final String NUM =
            "\\d{1,3}(?:[. ]\\d{3})*(?:[,.]\\d{1,2})?|\\d{4,}(?:[,.]\\d{1,2})?";

    /**
     * Suffix style: number → €, EUR, euros.
     * No trailing {@code \b} after € (it is not a {@code \w} character).
     * EUR/euros require {@code (?!\w)} to prevent partial-word matches.
     */
    static final Pattern P_SUFFIX = Pattern.compile(
            "(?:" + NUM + ")\\s*(?:€|(?:EUR|euros?)(?!\\w))",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Prefix style: €, EUR, euros → number.
     */
    static final Pattern P_PREFIX = Pattern.compile(
            "(?:€|(?:EUR|euros?)(?!\\w))\\s*(?:" + NUM + ")",
            Pattern.CASE_INSENSITIVE
    );

    private MonetaryClaimExtractor() {}

    /** Returns every raw monetary match found in {@code text}, in encounter order. */
    public static List<String> extractAll(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        collect(P_SUFFIX, text, out);
        collect(P_PREFIX, text, out);
        return List.copyOf(out);
    }

    private static void collect(Pattern p, String text, List<String> out) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            out.add(m.group().strip());
        }
    }
}
