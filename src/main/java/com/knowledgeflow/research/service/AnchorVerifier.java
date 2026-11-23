package com.knowledgeflow.research.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Presença LITERAL: verifica que uma âncora proposta existe literalmente na evidência e que os números
 * da afirmação aparecem na evidência. Normaliza apenas espaços, caracteres invisíveis, aspas/hífens
 * tipográficos e maiúsculas; não aceita paráfrase. Não avalia se a âncora sustenta a afirmação — isso
 * é o suporte substantivo ({@link SupportVerifier}).
 * <p>
 * Âncoras com menos de {@link #MIN_ANCHOR_LENGTH} caracteres só são aceites quando correspondem
 * exactamente a uma unidade legal identificável e pertinente (KF-M02/CORR-03, F2): uma linha completa
 * da evidência, iniciada por um marcador de número ou alínea (ex.: "4 - (Revogado.)", "b) …"), cujo
 * identificador consta da afirmação ("n.º 4", "alínea b)"). Fragmentos arbitrários continuam rejeitados.
 */
@Component
public class AnchorVerifier {

    /** Âncoras mais curtas do que isto não provam nada ("a", "IVA"). */
    static final int MIN_ANCHOR_LENGTH = 20;

    private static final Pattern INVISIBLE = Pattern.compile("[\\u200B\\u200C\\u200D\\uFEFF\\u00AD]");
    private static final Pattern UNIT_NUMBER = Pattern.compile("^(\\d+)(?:\\.º)?\\s*[-–]\\s*\\S");
    private static final Pattern UNIT_ITEM = Pattern.compile("^([a-z]|\\d+)\\)\\s*\\S");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");

    public boolean isAnchored(String anchor, String evidenceContent) {
        if (anchor == null || evidenceContent == null) return false;
        String a = normalize(anchor);
        return a.length() >= MIN_ANCHOR_LENGTH && normalize(evidenceContent).contains(a);
    }

    /**
     * Presença literal para a afirmação dada: âncora longa contida na evidência, ou âncora curta que é
     * exactamente uma unidade legal completa (linha) cujo identificador consta da afirmação.
     */
    public boolean isAnchored(String anchor, String evidenceContent, String statement) {
        if (isAnchored(anchor, evidenceContent)) return true;
        return isLegalUnitAnchor(anchor, evidenceContent, statement);
    }

    static boolean isLegalUnitAnchor(String anchor, String evidenceContent, String statement) {
        if (anchor == null || evidenceContent == null || statement == null) return false;
        String a = normalize(anchor);
        if (a.isEmpty()) return false;
        boolean wholeLine = false;
        for (String line : evidenceContent.split("\n")) {
            if (normalize(line).equals(a)) {
                wholeLine = true;
                break;
            }
        }
        if (!wholeLine) return false;
        String s = normalize(statement);
        Matcher number = UNIT_NUMBER.matcher(a);
        if (number.find()) {
            String id = number.group(1);
            return Pattern.compile("(n\\.?\\s?[ºo°]\\s*|número\\s+)" + id + "(?!\\d)").matcher(s).find();
        }
        Matcher item = UNIT_ITEM.matcher(a);
        if (item.find()) {
            String id = Pattern.quote(item.group(1));
            return Pattern.compile("(alínea|alinea|n\\.?\\s?[ºo°])\\s*" + id + "\\)?(?![\\w])").matcher(s).find();
        }
        return false;
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
