package com.knowledgeflow.ai.grounding;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical identity of a deadline, for grounding validation.
 *
 * <p>Two forms are recognised:
 * <ul>
 *   <li><b>cardinal</b> — {@code 30 dias}, {@code 5 dias úteis}, {@code 10 anos civis}, {@code 3 meses};</li>
 *   <li><b>ordinal</b> — {@code 5.º dia útil}, {@code 15.º dia do mês seguinte}, written {@code 5.º},
 *       {@code 5º}, {@code 5.°}, {@code 5°} or {@code 5.o}, with ordinary or no-break spaces.</li>
 * </ul>
 * The identity is: form (cardinal/ordinal), number, unit (dia/mês/ano) and the material qualifier
 * attached to the unit — {@code útil}, {@code civil}, {@code seguido}, {@code consecutivo},
 * {@code de calendário} — plus, for ordinals, the reference month ({@code do mês seguinte},
 * {@code do mês anterior}; {@code dia 15 do mês seguinte} ≡ {@code 15.º dia do mês seguinte}).
 * Numbers are whole: {@code 1.000} (thousands) and {@code 2,5} ≡ {@code 2.5} (decimal). Two deadlines are equivalent only when the whole identity is equal:
 * 5.º ≠ 15.º ≠ 10.º, {@code 5 dias} ≠ {@code 5 dias úteis}, {@code 10 anos} ≠ {@code 10 anos civis},
 * {@code 5.º dia útil} ≠ {@code 5 dias úteis}. Dropping or adding a qualifier changes the legal
 * meaning of the deadline, so it is never treated as the same claim (fail-closed). No synonym is
 * collapsed between qualifiers. Comparison is by identity, never by substring.
 */
final class DeadlineReference {

    /** A deadline found in a text: canonical key and the text as written. */
    record Ref(String key, String text) {
    }

    /** Whitespace including no-break spaces (U+00A0, U+202F), which Java's \s does not match. */
    private static final String WS = "[\\s\\u00A0\\u202F]";

    private static final String QUALIFIER =
            "(?:" + WS + "+(úteis|útil|uteis|util|civis|civil|seguid[oa]s?|consecutiv[oa]s?|de" + WS + "+calendário"
                    + "|de" + WS + "+calendario))?(?!\\p{L})";

    /** Number: "1.000" (thousands), "2,5" / "2.5" (decimal) or an integer — always whole, never a tail. */
    private static final String NUMBER = "(\\d{1,3}(?:\\.\\d{3})+(?!\\d)|\\d+(?:[.,]\\d+)?)";

    /** "30 dias", "5 dias úteis", "2,5 anos". */
    private static final Pattern CARDINAL = Pattern.compile(
            "(?iu)(?<![\\p{L}\\d.,])" + NUMBER + WS + "+(dias?|meses|m[eê]s|anos?)(?!\\p{L})" + QUALIFIER);

    /** "5.º dia útil", "15.º dia do mês seguinte" — ordinal mark required. */
    private static final Pattern ORDINAL = Pattern.compile(
            "(?iu)(?<![\\p{L}\\d.,/])(\\d+)(?:\\.?" + WS + "*[º°]|\\.(?-i:o))" + WS + "*(dia|m[eê]s|ano)(?!\\p{L})"
                    + QUALIFIER
                    + "(?:" + WS + "+d[oa]" + WS + "+m[eê]s" + WS + "+(seguinte|anterior)(?!\\p{L}))?");

    /** "dia 15 do mês seguinte" — same identity as "15.º dia do mês seguinte". */
    private static final Pattern DAY_OF_MONTH = Pattern.compile(
            "(?iu)(?<!\\p{L})dia" + WS + "+(\\d{1,2})(?!\\d)" + WS + "+d[oa]" + WS + "+m[eê]s" + WS
                    + "+(seguinte|anterior)(?!\\p{L})");

    private DeadlineReference() {
    }

    /** Every deadline in the text, in order of appearance. */
    static List<Ref> findAll(String text) {
        List<Ref> refs = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return refs;
        }
        Matcher c = CARDINAL.matcher(text);
        while (c.find()) {
            refs.add(new Ref(key("C", c.group(1), c.group(2), c.group(3), null), c.group().strip()));
        }
        Matcher o = ORDINAL.matcher(text);
        while (o.find()) {
            refs.add(new Ref(key("O", o.group(1), o.group(2), o.group(3), o.group(4)), o.group().strip()));
        }
        Matcher d = DAY_OF_MONTH.matcher(text);
        while (d.find()) {
            refs.add(new Ref(key("O", d.group(1), "dia", null, d.group(2)), d.group().strip()));
        }
        return refs;
    }

    /** Canonical keys of every deadline in the text. */
    static Set<String> extractAll(String text) {
        Set<String> keys = new LinkedHashSet<>();
        findAll(text).forEach(r -> keys.add(r.key()));
        return keys;
    }

    private static String key(String form, String number, String unit, String qualifier, String month) {
        String n = number.matches("\\d{1,3}(?:\\.\\d{3})+") ? number.replace(".", "") : number.replace(',', '.');
        n = n.replaceFirst("^0+(?=\\d)", "");
        String u = fold(unit);
        String unitKey = u.startsWith("dia") ? "DIA" : u.startsWith("ano") ? "ANO" : "MES";
        StringBuilder key = new StringBuilder("DEADLINE:").append(form).append(':').append(n).append(':').append(unitKey);
        if (qualifier != null) {
            String q = fold(qualifier);
            key.append(':').append(q.startsWith("ute") || q.startsWith("uti") ? "UTIL"
                    : q.startsWith("civ") ? "CIVIL"
                    : q.startsWith("seguid") ? "SEGUIDO"
                    : q.startsWith("consecutiv") ? "CONSECUTIVO"
                    : "CALENDARIO");
        }
        if (month != null) {
            key.append(":MES_").append(fold(month).toUpperCase(Locale.ROOT));
        }
        return key.toString();
    }

    /** Lower case, no accents, single spaces. */
    private static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("[\\s\\u00A0\\u202F]+", " ").strip();
    }
}
