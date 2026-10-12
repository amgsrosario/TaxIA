package com.knowledgeflow.ai.grounding;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical identity of a reference to a legal instrument (diploma), for grounding validation.
 *
 * <p>Recognised types: Decreto-Lei ({@code Decreto-Lei}, {@code Decreto Lei}, {@code Decreto -Lei},
 * {@code DL}, {@code D.L.}), Portaria, Despacho, Circular and Ofício-Circulado — the types the
 * validator already treated as legal references. The number sign is optional and may be written
 * {@code n.º}, {@code nº}, {@code n.°}, {@code n.} or {@code n}.
 *
 * <p>Identity: type + number (with its letter suffix, {@code 442-A}) + year as written. So
 * {@code Decreto-Lei n.º 28/2019} ≡ {@code DL 28/2019} ≡ {@code D.L. nº 28/2019}, but 28/2019 ≠
 * 198/2012 ≠ 49/2025 ≠ 28/2020 ≠ 28 (number without year) and {@code 442-A/88} ≠ {@code 442-A/1988}
 * (two-digit years are not expanded: fail-closed). No ontology of diplomas, no substring matching.
 */
final class LegalInstrumentReference {

    /** A reference found in a text: canonical key ({@code DL:28/2019}) and the text as written. */
    record Ref(String key, String text) {
    }

    private static final String WS = "[\\s\\u00A0\\u202F]";
    private static final String DASH = "[-\\u2010-\\u2015\\u2212]";

    /** Type groups: 1 Decreto-Lei (words), 2 DL / D.L. (upper case only), 3 other types. */
    private static final String TYPE =
            "(?:(decreto" + WS + "*" + DASH + WS + "*lei|decreto" + WS + "+lei)"
                    + "|((?-i:D\\.?" + WS + "?L)\\.?)"
                    + "|(portaria|despacho|circular|ofício" + WS + "*" + DASH + "?" + WS + "*circulado"
                    + "|oficio" + WS + "*" + DASH + "?" + WS + "*circulado))";

    /** Optional number sign (group 4): n.º / nº / n.° / n° / n.o / n. / n / número. */
    private static final String NUMBER_SIGN =
            "(n(?:\\.?" + WS + "*[º°]|\\.(?-i:o)(?!\\p{L})|\\.)?" + WS + "*|n[úu]mero" + WS + "+)?";

    /** Number (group 5: "28", "442-A", "30.250") and optional year (group 6). */
    private static final String NUMBER =
            "(\\d{1,3}(?:\\.\\d{3})+(?!\\d)|\\d+)(" + DASH + "\\p{L}{1,2})?(?:" + WS + "*/" + WS + "*(\\d{2,4}))?(?![\\d\\p{L}])";

    private static final Pattern REF = Pattern.compile(
            "(?iu)(?<![\\p{L}\\d])" + TYPE + WS + "*" + NUMBER_SIGN + NUMBER);

    private LegalInstrumentReference() {
    }

    /** Every instrument reference in the text, in order of appearance. */
    static List<Ref> findAll(String text) {
        List<Ref> refs = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return refs;
        }
        Matcher m = REF.matcher(text);
        while (m.find()) {
            // "despacho"/"circular" are also ordinary words ("o despacho 15 dias depois", "economia
            // circular 2030"): only a reference with a number sign or a year counts.
            boolean ordinaryWord = m.group(3) != null
                    && m.group(3).toLowerCase(Locale.ROOT).matches("despacho|circular");
            if (ordinaryWord && m.group(4) == null && m.group(7) == null) continue;
            refs.add(new Ref(key(m), m.group().strip()));
        }
        return refs;
    }

    /** Canonical keys of every instrument reference in the text. */
    static Set<String> extractAll(String text) {
        Set<String> keys = new LinkedHashSet<>();
        findAll(text).forEach(r -> keys.add(r.key()));
        return keys;
    }

    private static String key(Matcher m) {
        String type;
        if (m.group(1) != null || m.group(2) != null) {
            type = "DL";
        } else {
            String t = m.group(3).toLowerCase(Locale.ROOT);
            type = t.startsWith("portaria") ? "PORTARIA"
                    : t.startsWith("despacho") ? "DESPACHO"
                    : t.startsWith("circular") ? "CIRCULAR"
                    : "OFICIO_CIRCULADO";
        }
        String number = m.group(5).replace(".", "").replaceFirst("^0+(?=\\d)", "")
                + (m.group(6) == null ? "" : "-" + m.group(6).substring(1).toUpperCase(Locale.ROOT));
        String year = m.group(7);
        return type + ":" + number + (year == null ? "" : "/" + year);
    }
}
