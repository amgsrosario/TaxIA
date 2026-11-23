package com.knowledgeflow.research.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verificação determinística do contexto normativo de uma proposição, anterior à segunda barreira (KF-PILOT/R-3). Se o contexto
 * necessário à interpretação da unidade não estiver demonstradamente preservado na proposição, ela não é demonstrada e o percurso
 * termina em resposta-limite, sem chamar o modelo e seja qual for o veredicto que o modelo daria.
 * <ol>
 *   <li>Cada negação, excepção ou condição conhecida da unidade (enunciado, linhas da âncora e continuações) tem de estar, com a oração
 *       até à pontuação seguinte, <b>literalmente</b> na afirmação ou na âncora de uma condição verificada: uma percentagem de palavras
 *       não basta, porque «falte» e «exista» invertem a condição (parecer R123, F3).</li>
 *   <li>O efeito fiscal afirmado (isenção, dedução, tributação…) e os valores afirmados têm de constar da unidade (parecer R123, F3, F4).</li>
 *   <li>Uma alínea ou verba sem enunciado identificado não é verificável.</li>
 *   <li>O enunciado introdutório tem de estar ligado à afirmação por pelo menos metade das suas palavras de conteúdo.</li>
 * </ol>
 * É conservador e lexical. Recusa contextos não preservados nos casos conhecidos; não demonstra a correcção jurídica da proposição,
 * nem cobre formas fora do léxico cujo enunciado partilhe vocabulário e efeito com a afirmação.
 */
public final class NormativeContextGuard {

    /** Prefixos das razões de recusa determinística antes da barreira: não são falhas técnicas. */
    static final List<String> REFUSAL_PREFIXES = List.of("contexto normativo não preservado", "enunciado da norma",
            "efeito fiscal afirmado", "valor afirmado", "ocorrência da âncora não identificável");

    private static final Pattern SUJEITO_PASSIVO = Pattern.compile("sujeitos? passivos?");
    /** Valor com unidade, completo: «5 euros» não está em «15 euros» (parecer R123 R2, B). */
    private static final Pattern VALUE = Pattern.compile("(?<![\\d.,\\p{L}])(?:(?<!\\d )- *)?\\d+(?:[.,]\\d+)* *(%|€|euros?|dias?|meses|mês|anos?)(?![\\p{L}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /**
     * Número completo e com sinal, contíguo ou separado por espaço (pareceres R123 R3, N1, e R4, N3), sem identificadores: artigo 9.º, n.º 5,
     * alínea 1), Lei 2/2020. Um traço depois de um algarismo («1 - 15», «10 - 15») separa e não é sinal. Valores por extenso não são cobertos.
     */
    private static final Pattern NUMBER = Pattern.compile("(?<![\\d.,/º\\p{L}])(?<!n\\.º )(?:(?<!\\d )- *)?\\d+(?:[.,]\\d+)*(?![\\d/)ºª]|\\.º)");

    /** Lista fechada do texto comparável: letras, algarismos 0 a 9 e pontuação comum. Tudo o resto é recusado (parecer R123 R6, N1). */
    private static final Pattern UNSUPPORTED = Pattern.compile("[^\\p{L}0-9 \\-.,;:!?()\\[\\]/%€«»\"'“”‘’…§°+=&*_#@]");

    private static final double MIN_CHAPEAU_LINK = 0.5;
    private static final int STEM = 5;

    private NormativeContextGuard() {}

    /** A razão é uma recusa conservadora desta verificação, e não uma falha técnica do sistema (parecer R123, F6). */
    public static boolean isDeterministicRefusal(String reason) {
        return reason != null && REFUSAL_PREFIXES.stream().anyMatch(reason::startsWith);
    }

    /** @return motivo pelo qual o contexto não está preservado; vazio se estiver */
    static Optional<String> violation(String statement, List<String> conditionAnchors, SupportVerifier.NormativeUnit unit) {
        if (unit.chapeau().isBlank() && !unit.body().isEmpty() && SupportVerifier.isItem(unit.body().get(0))) {
            return Optional.of("enunciado da norma não identificado para a alínea ou verba: «%s»".formatted(unit.body().get(0)));
        }
        String folded = fold(statement);
        List<String> anchors = conditionAnchors == null ? List.of() : conditionAnchors.stream().map(AnchorVerifier::normalize).toList();
        // Corpo e continuações formam uma só sequência: uma oração pode estar repartida por linhas (parecer R123 R3, N2).
        List<String> lines = new ArrayList<>(unit.body());
        lines.addAll(unit.continuations());
        lines = new ArrayList<>(List.of(String.join(" ", lines)));
        if (!unit.chapeau().isBlank()) lines.add(unit.chapeau());
        lines.addAll(unit.outer());
        for (String raw : lines) {
            String line = AnchorVerifier.normalize(raw);
            Matcher m = LegalContextLexicon.MARKER.matcher(line);
            while (m.find()) {
                int end = clauseEnd(line, m.end());
                String clause = line.substring(m.start(), end).strip();
                if (!inAnchor(line, m.start(), end, anchors) && !AnchorVerifier.normalize(statement).contains(clause)) {
                    return Optional.of("contexto normativo não preservado: «%s»".formatted(line.substring(m.start(), end).strip()));
                }
            }
        }
        String unitText = fold(String.join(" ", lines));
        String claims = SUJEITO_PASSIVO.matcher(folded).replaceAll(" ");
        for (List<String> effect : LegalContextLexicon.EFFECTS) {
            boolean claimed = effect.stream().anyMatch(stem -> hasStem(claims, stem));
            if (claimed && effect.stream().noneMatch(stem -> hasStem(unitText, stem))) {
                return Optional.of("efeito fiscal afirmado que não consta da unidade normativa: «%s»".formatted(effect.get(0)));
            }
        }
        Optional<String> value = changedValue(statement, lines, anchors);
        if (value.isPresent()) return value;
        if (!unit.chapeau().isBlank() && !linked(unit.chapeau(), statement, conditionAnchors)) {
            return Optional.of("enunciado da norma sem ligação à afirmação: «%s»".formatted(unit.chapeau()));
        }
        return Optional.empty();
    }

    /** Cada valor com unidade e cada número afirmados têm de constar, completos, da unidade ou de uma condição verificada. */
    private static Optional<String> changedValue(String statement, List<String> lines, List<String> anchors) {
        String claimed = canonicalNumerals(AnchorVerifier.normalize(statement));
        String evidence = canonicalNumerals(AnchorVerifier.normalize(String.join(" ", lines) + " " + String.join(" ", anchors)));
        if (UNSUPPORTED.matcher(claimed).find() || UNSUPPORTED.matcher(evidence).find()) {
            return Optional.of("valor afirmado em texto com representação não suportada");
        }
        for (Pattern pattern : List.of(VALUE, NUMBER)) {
            Set<String> known = tokens(pattern, evidence);
            for (String token : tokens(pattern, claimed)) {
                if (!known.contains(token)) return Optional.of("valor afirmado que não consta da unidade normativa: «%s»".formatted(token));
            }
        }
        return Optional.empty();
    }

    /**
     * Forma canónica antes de extrair valores: espaços e traços por classe Unicode, sem compatibilidade NFKC, que transformaria «15²» em
     * «152» (parecer R123 R6, N2). O que sobrar fora de {@link #UNSUPPORTED} leva à recusa, em vez de se tentar enumerar representações.
     */
    private static String canonicalNumerals(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("[\\p{Z}\\s\\x{0085}]+", " ").replaceAll("[\\p{Pd}−]", "-");
    }

    private static Set<String> tokens(Pattern pattern, String text) {
        Set<String> out = new HashSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) out.add(m.group().replace(" ", "").toLowerCase(Locale.ROOT));
        return out;
    }

    private static boolean inAnchor(String line, int start, int end, List<String> anchors) {
        for (String anchor : anchors) {
            int i = anchor.isEmpty() ? -1 : line.indexOf(anchor);
            if (i >= 0 && i <= start && end <= i + anchor.length()) return true;
        }
        return false;
    }

    private static boolean hasStem(String folded, String stem) {
        return Pattern.compile("(?<![a-z])" + stem).matcher(folded).find();
    }

    private static boolean linked(String chapeau, String statement, List<String> conditionAnchors) {
        Set<String> content = stems(chapeau);
        content.removeIf(s -> LegalContextLexicon.GENERIC.stream().anyMatch(g -> g.startsWith(s) || s.equals(g)));
        if (content.isEmpty()) return true;
        Set<String> present = stems(statement);
        if (conditionAnchors != null) conditionAnchors.forEach(a -> present.addAll(stems(a)));
        return content.stream().filter(present::contains).count() >= Math.ceil(content.size() * MIN_CHAPEAU_LINK);
    }

    /** Fim da oração: a pontuação seguinte, sem contar a que separa algarismos («1.000», «2,5»). */
    private static int clauseEnd(String line, int from) {
        for (int i = from; i < line.length(); i++) {
            char c = line.charAt(i);
            if (";:".indexOf(c) >= 0) return i;
            if (",.".indexOf(c) >= 0 && !(i > 0 && i + 1 < line.length() && Character.isDigit(line.charAt(i - 1)) && Character.isDigit(line.charAt(i + 1)))) return i;
        }
        return line.length();
    }

    /** Radicais das palavras de conteúdo (pelo menos 4 letras). */
    private static Set<String> stems(String text) {
        Set<String> out = new HashSet<>();
        Arrays.stream(fold(text).split("[^a-z]+")).filter(w -> w.length() >= 4)
                .forEach(w -> out.add(w.length() > STEM ? w.substring(0, STEM) : w));
        return out;
    }

    private static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
