package com.knowledgeflow.research.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Verifica que uma âncora proposta existe literalmente na evidência e que os números da afirmação
 * aparecem na evidência. Normaliza apenas espaços, caracteres invisíveis, aspas/hífens tipográficos
 * e maiúsculas; não aceita paráfrase.
 */
@Component
public class AnchorVerifier {

    /** Âncoras mais curtas do que isto não provam nada ("a", "IVA"). */
    static final int MIN_ANCHOR_LENGTH = 20;

    private static final Pattern INVISIBLE = Pattern.compile("[\\u200B\\u200C\\u200D\\uFEFF\\u00AD]");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");

    public boolean isAnchored(String anchor, String evidenceContent) {
        if (anchor == null || evidenceContent == null) return false;
        String a = normalize(anchor);
        return a.length() >= MIN_ANCHOR_LENGTH && normalize(evidenceContent).contains(a);
    }

    /** Todos os números da afirmação constam da evidência (valores, percentagens, prazos, artigos). */
    public boolean numbersSupported(String statement, String evidenceContent) {
        if (statement == null) return true;
        String evidence = normalize(evidenceContent == null ? "" : evidenceContent);
        Matcher m = NUMBER.matcher(statement);
        while (m.find()) {
            if (!containsNumber(evidence, m.group())) return false;
        }
        return true;
    }

    private static boolean containsNumber(String text, String number) {
        Matcher m = Pattern.compile("(?<![\\d.,])" + Pattern.quote(number) + "(?![\\d]|[.,]\\d)").matcher(text);
        return m.find();
    }

    static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFC);
        n = INVISIBLE.matcher(n).replaceAll("");
        n = n.replace(' ', ' ')
                .replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'')
                .replace('–', '-').replace('—', '-');
        return n.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }
}
