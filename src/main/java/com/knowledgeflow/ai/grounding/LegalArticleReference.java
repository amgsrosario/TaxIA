package com.knowledgeflow.ai.grounding;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical identity of a legal article reference, for grounding validation.
 *
 * <p>The same article can be written in several ways — {@code artigo 52.º}, {@code art. 52.º},
 * {@code art.º 52}, {@code art 52}, {@code (art. 52.º, n.º 1, …)}, {@code artigos 19.º e 20.º}
 * — and all of them must count as the same reference(s). Two references are equivalent only when
 * the article number and the letter suffix are exactly equal: {@code 52} never matches
 * {@code 152}, {@code 5} or {@code 52.º-A}. The comparison is by identity, never by substring.
 *
 * <p>Fail-closed rules: any letters attached to the number (after an optional dash of any kind:
 * {@code -}, {@code –}, {@code ‑}, {@code −}…) become part of the identity, so an unusual suffix
 * produces a different identity and is not supported by the bare article.
 *
 * <p>Scope: article identity only. Paragraphs ({@code n.º 1}) and the code/diploma name are not
 * part of the identity, as before.
 */
final class LegalArticleReference {

    /** A reference found in a text: canonical key ({@code ART:52}, {@code ART:135-D}) and the text. */
    record Ref(String key, String text) {
    }

    /** Whitespace including no-break spaces (U+00A0, U+202F), which Java's \s does not match. */
    private static final String WS = "[\\s\\u00A0\\u202F]";

    /** Any dash before a suffix: ASCII hyphen, U+2010–U+2015 (incl. en/em dash), minus, small/fullwidth hyphen. */
    private static final String DASH = "[-\\u2010-\\u2015\\u2212\\uFE63\\uFF0D]";

    /**
     * One article — groups: number, ordinal ({@code .º}, {@code º}, {@code °}, {@code ª}, or a
     * bare lowercase {@code o} not followed by a letter), attached suffix (all attached letters).
     */
    private static final String ARTICLE =
            "(\\d+)(\\.?" + WS + "*[º°ª]|(?-i:o)(?!\\p{L}))?(?:" + DASH + "?(\\p{L}+))?";

    /** A list item is never a quantity or date: not followed by a digit, %, €, a unit or "de". */
    private static final String NOT_A_QUANTITY =
            "(?!" + WS + "*(?:\\d|%|€|(?:dias?|meses?|anos?|euros?|de)(?!\\p{L})))";

    /** Singular prefix: artigo / art. / art.º / artº / art (not preceded by a letter or digit). */
    private static final String SINGULAR =
            "(?:artigo" + WS + "+|art(?:\\.º?|º)" + WS + "*|art" + WS + "+)";

    /** Plural prefix: artigos / arts. / arts — followed by a list "19.º e 20.º" or "19.º, 20.º". */
    private static final String PLURAL =
            "(?:artigos" + WS + "+|arts(?:\\.º?|º)" + WS + "*|arts" + WS + "+)";

    private static final Pattern HEAD = Pattern.compile(
            "(?iu)(?<![\\p{L}\\d])(" + PLURAL + "|" + SINGULAR + ")" + ARTICLE);

    /** Next element of a plural list: ", 20.º" or " e 20.º". */
    private static final Pattern LIST_ITEM = Pattern.compile(
            "(?iu)(?:" + WS + "*," + WS + "*|" + WS + "+e" + WS + "+)" + ARTICLE + NOT_A_QUANTITY);

    private LegalArticleReference() {
    }

    /** Every article reference in the text, in order of appearance (list items expanded). */
    static List<Ref> findAll(String text) {
        List<Ref> refs = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return refs;
        }
        Matcher head = HEAD.matcher(text);
        while (head.find()) {
            refs.add(new Ref(key(head.group(2), head.group(4)), head.group().strip()));
            boolean plural = head.group(1).toLowerCase(Locale.ROOT).startsWith("artigos")
                    || head.group(1).toLowerCase(Locale.ROOT).startsWith("arts");
            if (!plural) {
                continue;
            }
            // If the first article carries an ordinal ("19.º"), every list item must too: "artigos
            // 19.º e 20.º, 30 dias" never turns 30 into an article.
            boolean ordinalRequired = head.group(3) != null;
            Matcher item = LIST_ITEM.matcher(text);
            int position = head.end();
            item.region(position, text.length());
            while (item.lookingAt() && (!ordinalRequired || item.group(2) != null)) {
                refs.add(new Ref(key(item.group(1), item.group(3)), "artigo " + item.group().replaceFirst(
                        "^(?iu)" + WS + "*(?:,|e)" + WS + "*", "").strip()));
                position = item.end();
                item.region(position, text.length());
            }
        }
        return refs;
    }

    /** Canonical keys of every article reference in the text. */
    static Set<String> extractAll(String text) {
        Set<String> keys = new LinkedHashSet<>();
        findAll(text).forEach(r -> keys.add(r.key()));
        return keys;
    }

    private static String key(String number, String suffix) {
        String n = number.replaceFirst("^0+(?=\\d)", "");
        return suffix == null ? "ART:" + n : "ART:" + n + "-" + suffix.toUpperCase(Locale.ROOT);
    }
}
