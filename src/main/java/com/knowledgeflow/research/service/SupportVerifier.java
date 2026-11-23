package com.knowledgeflow.research.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Suporte substantivo de uma proposição (KF-M02/CORR-03, F1), separado da presença literal da
 * âncora ({@link AnchorVerifier}).
 * <p>
 * Uma âncora literal prova que o texto existe na evidência, não que sustente a afirmação. Aqui
 * exige-se que os termos de conteúdo da afirmação estejam cobertos pela <b>unidade legal que contém
 * a âncora</b>: a(s) linha(s) da âncora, o enunciado introdutório de que dependem (a linha anterior
 * terminada em ":" quando a âncora é um número ou alínea), a epígrafe do artigo, a referência legal
 * e a identificação do imposto/diploma. A cobertura mínima é {@link #MIN_COVERAGE}.
 * <p>
 * Não usa listas de artigos nem de expressões jurídicas: só uma lista de palavras funcionais do
 * português (artigos, preposições, conjunções, verbos auxiliares) e um radical de 5 letras sem
 * acentos. É uma verificação de cobertura lexical, não de implicação lógica: reduz falsos positivos
 * como "âncora sobre residência para uma afirmação sobre deduções", mas não prova correcção jurídica
 * (que continua sujeita à validação humana e à resposta com limitações).
 * <p>
 * Regra estrutural adicional: uma unidade cujo texto consolidado é apenas uma marca de revogação
 * ("4 - (Revogado.)") só sustenta afirmações sobre essa revogação — nunca que a disposição se aplica.
 * Também não sustenta conclusões sobre aplicabilidade, efeitos ou períodos (KF-M02/CORR-11,
 * {@link TemporalScope}): sem data nem diploma revogatório, só o estado actual está provado.
 * <p>
 * <b>A cobertura lexical é necessária mas nunca suficiente</b> (KF-M02/CORR-04). Partilhar vocabulário
 * com a norma não prova o sentido: "aplicam-se apenas aos residentes" e "aplicam-se a não residentes"
 * partilham quase todos os termos. Por isso, além da cobertura, exigem-se — de forma conservadora, e
 * com base em conectores gramaticais do português, não em listas de artigos ou de expressões jurídicas:
 * <ol>
 *   <li><b>polaridade</b>: nenhum termo partilhado pode estar negado ("não", "nem", "nunca",
 *       "nenhum", "sem") num lado e afirmado no outro; a negação vale até ao fim da oração (próxima
 *       pontuação), não um número fixo de palavras (KF-M02/ARCH-01, bypass de negação distante);</li>
 *   <li><b>âmbito restritivo</b>: se a norma restringe ("apenas", "só", "somente", "exclusivamente",
 *       "unicamente"), a afirmação tem de restringir; e não pode introduzir uma restrição que a norma
 *       não tem;</li>
 *   <li><b>âmbito delimitado</b> (KF-M02/POST-REAL07): cada número que a norma usa para delimitar o seu objecto
 *       ("as deduções previstas no n.º 1") tem de constar da afirmação — uma regra do n.º 1 não sustenta uma afirmação
 *       sobre o artigo inteiro;</li>
 *   <li><b>condições e excepções</b>: CADA qualificação da norma tem de estar coberta por uma condição
 *       estruturada e ancorada (KF-M02/ARCH-01: uma condição ancorada já não desbloqueia as outras).
 *       Qualificação = cada oração iniciada por um conector de condição/excepção ("se", "desde que",
 *       "quando", "salvo", "excepto", "sem prejuízo", "nos casos", "mediante", "caso") nas linhas
 *       normativas da unidade, até à pontuação seguinte; e cada alínea irmã do mesmo enunciado
 *       introdutório que seja ela própria uma condição (começa por esse conector, ou o enunciado declara
 *       as alíneas cumulativas). Coberta = a âncora de uma condição está nessa linha e sobrepõe-se à
 *       oração. A afirmação não pode introduzir uma condição/excepção inexistente na norma.</li>
 * </ol>
 * Qualquer falha → sem suporte → a proposição não é utilizável e a resposta passa a resposta-limite.
 * Estas regras rejeitam deliberadamente alguns casos verdadeiros (custo aceite: resposta-limite); não
 * detectam todas as inferências inválidas que partilhem vocabulário e polaridade com a norma.
 * <p>
 * <b>Estas verificações são só mecanismos de rejeição</b> (KF-M02/ARCH-01): passar aqui nunca
 * certifica suporte jurídico. A implicação é avaliada pela segunda barreira
 * ({@link EntailmentValidator}, decisão em {@link EntailmentGate}), que só corre sobre o que esta
 * camada não rejeitou.
 */
@Component
public class SupportVerifier {

    /** Fracção mínima de termos de conteúdo da afirmação cobertos pela unidade legal da âncora. */
    public static final double MIN_COVERAGE = 0.70;

    private static final int STEM = 5;
    /** Alínea ou item ("a)", "1)") — depende do enunciado introdutório do número em que está. */
    private static final Pattern ITEM = Pattern.compile("^\\s*([a-z]|\\d+)\\)");
    /** Número de topo ("4 - …") — unidade autónoma, sem enunciado introdutório. */
    private static final Pattern VERBA = Pattern.compile("^\\s*\\d+\\)");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d+(\\.º)?\\s*[-–]");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALPHA = Pattern.compile("[^a-z]+");
    private static final Pattern REVOCATION_MARKER =
            Pattern.compile("^\\W*(\\d+|[a-z])?\\W*\\(?\\s*revogad[oa]s?\\b[^a-z]*$");
    /** Referência a um número do mesmo artigo, em texto já normalizado ({@link #fold}). */
    private static final Pattern NUMBER_REFERENCE = Pattern.compile("(?<![\\p{L}\\d])(?:n\\.?\\s?[ºo°]\\s*|numero\\s+)(\\d+)(?!\\d)");
    private static final Set<String> NEGATIONS = Set.of("nao", "nem", "nunca", "nenhum", "nenhuma", "nenhuns", "nenhumas", "sem");
    private static final Set<String> RESTRICTIVE = Set.of("apenas", "so", "somente", "exclusivamente", "unicamente");
    /** Conectores de condição/excepção sem ambiguidade (usados na afirmação e na norma). */
    private static final Pattern QUALIFIER_STRICT = Pattern.compile(
            "(?<![\\p{L}-])(desde que|quando|salvo|excepto|exceto|sem prejuizo|nos casos|mediante|na condicao|caso)(?![\\p{L}])");
    /**
     * "se" condicional — só na norma, onde o erro é conservador. Excluídos o enclítico ("aplicam-se") e o
     * pronome proclítico atraído por negação, relativo ou advérbio ("a que se reportam", "não se aplica",
     * "quando se verifiquem"), que não introduzem condição.
     */
    private static final Pattern QUALIFIER_SE = Pattern.compile(
            "(?<![\\p{L}-])(?<!(?:que|nao|quando|onde|como|qual|quais|quem|cujo|cuja|ja|tambem|nunca|sempre) )se(?![\\p{L}-])");
    private static final Pattern TOKEN = Pattern.compile("[a-z]+(?:-[a-z]+)*|[,.;:!?()]");
    /** Fim de oração para o âmbito de uma qualificação. */
    private static final Pattern CLAUSE_END = Pattern.compile("[,;:.]");
    private static final Set<String> FUNCTION_WORDS = Set.copyOf(List.of(
            "o", "a", "os", "as", "um", "uma", "uns", "umas", "de", "do", "da", "dos", "das", "em", "no", "na",
            "nos", "nas", "por", "pelo", "pela", "pelos", "pelas", "para", "com", "sem", "sob", "sobre", "entre",
            "e", "ou", "que", "se", "nao", "sim", "ao", "aos", "num", "numa", "este", "esta", "estes", "estas",
            "esse", "essa", "esses", "essas", "isso", "isto", "aquele", "aquela", "seu", "sua", "seus", "suas",
            "lhe", "lhes", "ele", "ela", "eles", "elas", "mais", "menos", "muito", "pode", "podem", "deve",
            "devem", "tambem", "ainda", "apenas", "quando", "onde", "como", "qual", "quais", "cujo", "cuja",
            "ser", "sao", "foi", "foram", "sera", "esta", "estao", "estar", "ter", "tem", "tinha", "ha", "haver",
            "todo", "toda", "todos", "todas", "outro", "outra", "outros", "outras", "mesmo", "mesma", "caso",
            "termos", "seja", "sejam"));

    /**
     * Resultado: suportada ou não, cobertura, termos da afirmação sem cobertura e razões de recusa
     * (polaridade, âmbito, condições) — vazias quando suportada.
     */
    public record Result(boolean supported, double coverage, List<String> uncoveredTerms, List<String> reasons) {
        public Result(boolean supported, double coverage, List<String> uncoveredTerms) {
            this(supported, coverage, uncoveredTerms, List.of());
        }
    }

    /**
     * Suporte de uma condição/excepção: cobertura e polaridade. Não se lhe exige a restrição de
     * âmbito nem condições aninhadas — a condição é ela própria a restrição/qualificação da norma.
     */
    public Result checkCondition(String description, String anchor, String evidenceContent, String legalReference,
            String identification) {
        return check(description, anchor, evidenceContent, legalReference, identification, List.of(), true);
    }

    /**
     * Suporte de uma afirmação principal.
     *
     * @param conditionAnchors âncoras das condições/excepções estruturadas da proposição que já foram
     *        verificadas (literais e com suporte); cada qualificação da norma tem de ser coberta por uma
     */
    public Result check(String statement, String anchor, String evidenceContent, String legalReference,
            String identification, List<String> conditionAnchors) {
        return check(statement, anchor, evidenceContent, legalReference, identification, conditionAnchors, false);
    }

    private Result check(String statement, String anchor, String evidenceContent, String legalReference,
            String identification, List<String> conditionAnchors, boolean isCondition) {
        Set<String> wanted = stems(statement);
        if (wanted.isEmpty()) return new Result(false, 0.0, List.of(), List.of("afirmação sem termos de conteúdo"));
        if (isRevocationMarker(anchor) && !wanted.contains("revog")) {
            return new Result(false, 0.0, List.of(), List.of("a âncora é uma marca de revogação; a afirmação não trata da revogação"));
        }
        if (isRevocationMarker(anchor) && TemporalScope.exceedsRevocationEvidence(statement)) {
            return new Result(false, 0.0, List.of(), List.of("a âncora é uma marca de revogação sem data nem diploma "
                    + "revogatório; a afirmação conclui sobre aplicabilidade, efeitos ou períodos (âmbito temporal "
                    + "excede a evidência)"));
        }
        String unitLines = anchorUnit(anchor, evidenceContent);
        String context = unitContext(anchor, evidenceContent) + "\n" + nullToEmpty(legalReference) + "\n"
                + nullToEmpty(identification);
        Set<String> available = stems(context);
        List<String> uncovered = new ArrayList<>();
        for (String w : wanted) if (!available.contains(w)) uncovered.add(w);
        double coverage = (wanted.size() - uncovered.size()) / (double) wanted.size();

        List<String> reasons = new ArrayList<>();
        if (coverage < MIN_COVERAGE) reasons.add("cobertura lexical insuficiente");
        String st = fold(statement);
        String un = fold(unitLines);
        // 1. Polaridade
        var sp = polarity(st);
        var up = polarity(un);
        for (var e : sp.entrySet()) {
            Set<Boolean> unitPol = up.get(e.getKey());
            if (unitPol != null && !unitPol.containsAll(e.getValue())) {
                reasons.add("polaridade invertida no termo '" + e.getKey() + "'");
            }
        }
        // 2. Âmbito restritivo
        boolean unitRestricts = hasRestrictive(un);
        boolean statementRestricts = hasRestrictive(st);
        if (unitRestricts && !statementRestricts && !isCondition) reasons.add("restrição de âmbito da norma omitida");
        if (!unitRestricts && statementRestricts) reasons.add("restrição de âmbito inexistente na norma");
        // 3. Condições e excepções
        boolean unitQualified = QUALIFIER_STRICT.matcher(un).find() || QUALIFIER_SE.matcher(un).find();
        if (!isCondition) {
            List<String> uncoveredQualifiers = uncoveredQualifiers(anchor, evidenceContent,
                    conditionAnchors == null ? List.of() : conditionAnchors);
            if (!uncoveredQualifiers.isEmpty()) {
                reasons.add("condição/excepção da norma sem condição estruturada");
                uncoveredQualifiers.forEach(q -> reasons.add("qualificação sem condição estruturada: «" + q + "»"));
            }
        }
        if (QUALIFIER_STRICT.matcher(st).find() && !unitQualified) reasons.add("condição/excepção inexistente na norma");
        // 4. Âmbito delimitado (KF-M02/POST-REAL07): a norma delimita o seu objecto por número ("as deduções previstas no
        // n.º 1"); a afirmação tem de manter essa delimitação — não pode estendê-la ao artigo, ao imposto ou a outro número.
        if (!isCondition) {
            for (String ref : numberReferences(un)) {
                if (!numberReferences(st).contains(ref)) {
                    reasons.add("âmbito da norma alargado: a norma refere-se ao n.º " + ref + " e a afirmação não o delimita");
                }
            }
        }

        return new Result(reasons.isEmpty(), coverage, List.copyOf(uncovered), List.copyOf(reasons));
    }

    /**
     * Termo (radical) → polaridades em que ocorre (true = negado). A negação vale até ao fim da oração
     * (pontuação seguinte), sem limite de palavras: errar para o lado negado só aumenta as recusas.
     */
    static java.util.Map<String, Set<Boolean>> polarity(String folded) {
        java.util.Map<String, Set<Boolean>> out = new java.util.HashMap<>();
        var m = TOKEN.matcher(folded);
        boolean inNegatedClause = false;
        while (m.find()) {
            String t = m.group();
            if (t.length() == 1 && ",.;:!?()".contains(t)) {
                inNegatedClause = false;
                continue;
            }
            boolean prefixed = t.startsWith("nao-") || t.startsWith("sem-");
            if (NEGATIONS.contains(t)) {
                inNegatedClause = true;
                continue;
            }
            String word = prefixed ? t.substring(4) : t;
            boolean negated = prefixed || inNegatedClause;
            for (String part : word.split("-")) {
                if (part.length() < 3 || FUNCTION_WORDS.contains(part) || RESTRICTIVE.contains(part)) continue;
                String stem = part.length() > STEM ? part.substring(0, STEM) : part;
                out.computeIfAbsent(stem, k -> new java.util.HashSet<>()).add(negated);
            }
        }
        return out;
    }

    /**
     * Identificador de diploma («Diretiva n.º 2003/48/CE», «Lei n.º 2/2020», «Regulamento (UE) n.º 2016/679»): o número
     * identifica o diploma, não um número do artigo (KF-M02/REAL-08, correcção C1). Lista fechada de designações; entre a
     * designação e «n.º» só se admitem espaços (incluindo o não separável) e, opcionalmente, um qualificativo europeu
     * fechado — nunca um parêntese arbitrário («Lei (artigo 57.º) n.º 1» mantém o n.º 1). Sem janela de contexto.
     */
    private static final Pattern DIPLOMA_NUMBER = Pattern.compile(
            "(?<![\\p{L}-])(?:diretiva|directiva|regulamento|lei(?:[\\s\\u00a0]+organica)?|decreto-lei|decreto[\\s\\u00a0]+regulamentar"
                    + "|portaria|despacho)(?:[\\s\\u00a0]*\\((?:ue|ce|cee|euratom)(?:,[\\s\\u00a0]*euratom)?\\))?[\\s\\u00a0]*"
                    + "n\\.?[\\s\\u00a0]?[ºo°][\\s\\u00a0]*(\\d+)(?!\\d)");

    /**
     * Referências a números do artigo ("n.º 1", "n.o 3", "número 2"), sem o marcador da própria linha ("5 - …") e sem os
     * identificadores de diplomas (C1): «n.º 1 do artigo 57.º da Lei n.º 2/2020» refere o n.º 1, não o n.º 2.
     */
    static Set<String> numberReferences(String folded) {
        Set<Integer> diplomaNumbers = new java.util.HashSet<>();
        var d = DIPLOMA_NUMBER.matcher(folded);
        while (d.find()) diplomaNumbers.add(d.start(1));
        Set<String> refs = new LinkedHashSet<>();
        var m = NUMBER_REFERENCE.matcher(folded);
        while (m.find()) {
            if (diplomaNumbers.contains(m.start(1))) continue;
            refs.add(m.group(1));
        }
        return refs;
    }

    private static boolean hasRestrictive(String folded) {
        var m = TOKEN.matcher(folded);
        while (m.find()) if (RESTRICTIVE.contains(m.group())) return true;
        return false;
    }

    private static String fold(String text) {
        return text == null ? "" : DIACRITICS.matcher(Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)).replaceAll("");
    }

    /** Só as linhas normativas da unidade (âncora e enunciado introdutório), sem epígrafe nem referência. */
    static String anchorUnit(String anchor, String evidenceContent) {
        String unit = unitContext(anchor, evidenceContent);
        int nl = unit.indexOf('\n');
        return nl < 0 ? "" : unit.substring(nl + 1);
    }

    public static boolean isRevocationMarker(String anchor) {
        if (anchor == null) return false;
        String n = DIACRITICS.matcher(Normalizer.normalize(anchor.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)).replaceAll("");
        return REVOCATION_MARKER.matcher(n).matches();
    }

    /** Localização da âncora: linhas da evidência, janela mínima que a contém e enunciado introdutório (-1 se não há). */
    private record Located(String[] lines, int start, int end, int chapeau) {}

    private static Located locate(String anchor, String evidenceContent) {
        if (anchor == null || evidenceContent == null) return null;
        String[] lines = evidenceContent.split("\n");
        String a = AnchorVerifier.normalize(anchor);
        // Menor janela de linhas que contém a âncora (1 linha antes de 2, …, até 4).
        for (int span = 1; span <= 4; span++) {
            for (int i = 0; i + span <= lines.length; i++) {
                String joined = String.join(" ", java.util.Arrays.asList(lines).subList(i, i + span));
                if (AnchorVerifier.normalize(joined).contains(a)) {
                    return new Located(lines, i, i + span - 1, chapeau(lines, i));
                }
            }
        }
        return null;
    }

    /**
     * Enunciado introdutório de uma alínea ou verba, dentro do mesmo número (parecer R123, F2). Uma verba ({@code 30)}) é irmã das
     * outras verbas: o seu enunciado é o que as rege a todas, e nunca o de outra verba terminada em dois pontos. Uma alínea
     * ({@code a)}) pertence à verba ou número mais próximo: se esse não terminar em dois pontos, não tem enunciado.
     */
    private static int chapeau(String[] lines, int start) {
        if (!ITEM.matcher(lines[start]).find()) return -1;
        boolean verba = VERBA.matcher(lines[start]).find();
        for (int k = start - 1; k >= 0; k--) {
            String line = lines[k].strip();
            if (UNIT_HEADING.matcher(line).find()) return -1; // o enunciado de outro artigo não rege esta unidade (parecer R123 R2, C)
            boolean item = ITEM.matcher(line).find();
            if (verba) {
                if (item) continue; // verba irmã ou alínea de uma verba irmã
                if (line.endsWith(":") && startsVerbaSeries(lines, k)) return k;
            } else {
                if (VERBA.matcher(line).find()) return line.endsWith(":") ? k : -1;
                if (item) continue; // alínea irmã, mesmo terminada em dois pontos: não rege as seguintes
                if (line.endsWith(":")) return k;
            }
            if (NUMBERED.matcher(line).find()) return -1; // saiu do número a que pertence
        }
        return -1;
    }

    /** O enunciado de uma série de verbas é a linha que abre a verba 1). */
    private static boolean startsVerbaSeries(String[] lines, int k) {
        for (int i = k + 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) return lines[i].strip().startsWith("1)");
        }
        return false;
    }

    /**
     * Unidade legal da âncora: epígrafe (primeira linha da evidência), linha(s) que contêm a âncora e
     * enunciado introdutório quando a âncora está numa alínea. É também a unidade enviada à segunda
     * barreira e aquela a que a passagem citada tem de pertencer.
     */
    public static String unitContext(String anchor, String evidenceContent) {
        Located loc = locate(anchor, evidenceContent);
        if (loc == null) return "";
        StringBuilder unit = new StringBuilder(loc.lines()[0]);
        for (int i = loc.start(); i <= loc.end(); i++) unit.append('\n').append(loc.lines()[i]);
        if (loc.chapeau() >= 0) unit.append('\n').append(loc.lines()[loc.chapeau()]);
        return unit.toString();
    }

    private static final Pattern UNIT_HEADING = Pattern.compile("^(artigo|cap[íi]tulo|sec[çc][ãa]o|subsec[çc][ãa]o|t[íi]tulo)\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Uma linha continua a unidade salvo se abrir outra: alínea, número ou cabeçalho. Na dúvida, continua, porque perder uma ressalva é
     * pior do que recusar (parecer R123 R2, A). Só não continua uma enumeração sem marca jurídica, ou uma linha sem pontuação final
     * começada por maiúscula e seguida de cabeçalho, que é a epígrafe seguinte.
     */
    private static boolean isContinuation(String[] lines, int i) {
        String line = lines[i].strip();
        if (ITEM.matcher(line).find() || NUMBERED.matcher(line).find() || UNIT_HEADING.matcher(line).find()) return false;
        if (line.endsWith(":")) return LegalContextLexicon.MARKER.matcher(AnchorVerifier.normalize(line)).find();
        if (line.endsWith(".") || line.endsWith(";") || !Character.isUpperCase(line.codePointAt(0))) return true;
        for (int k = i + 1; k < lines.length; k++) {
            if (!lines[k].isBlank()) return !UNIT_HEADING.matcher(lines[k].strip()).find();
        }
        return true;
    }

    /** A linha é uma alínea ou verba, regida por um enunciado. */
    static boolean isItem(String line) {
        return ITEM.matcher(line).find();
    }

    /** Início de uma unidade autónoma: número com hífen ({@code 1 -}) ou verba ({@code 1)}). */
    private static final Pattern AUTONOMOUS_UNIT = Pattern.compile("^\\s*\\d+(\\.º)?\\s*([-–]|\\))");

    /**
     * A ocorrência da âncora é identificável: não atravessa outra unidade autónoma e o seu texto ocorre uma só vez na evidência.
     * Caso contrário não se sabe a que unidade legal pertence e nada se pode verificar nela (KF-PILOT/IT-2, pareceres R2-01,
     * R3-02 e R4-03).
     */
    public static boolean occurrenceIdentified(String anchor, String evidenceContent) {
        Located loc = locate(anchor, evidenceContent);
        if (loc == null) return false;
        for (int i = loc.start() + 1; i <= loc.end(); i++) {
            if (AUTONOMOUS_UNIT.matcher(loc.lines()[i]).find()) return false;
        }
        String a = AnchorVerifier.normalize(anchor);
        String all = AnchorVerifier.normalize(evidenceContent);
        return all.indexOf(a) == all.lastIndexOf(a);
    }

    /**
     * Enunciado introdutório imediatamente seguido das linhas da âncora, pela ordem do documento e na ocorrência localizada.
     * Vazio, o que mantém a recusa conservadora, se a âncora não for uma alínea, se o enunciado não lhe for contíguo ou se a
     * ocorrência não for identificável (KF-PILOT/IT-2).
     */
    public static String contiguousUnit(String anchor, String evidenceContent) {
        Located loc = locate(anchor, evidenceContent);
        if (loc == null || loc.chapeau() < 0 || loc.chapeau() != loc.start() - 1 || !occurrenceIdentified(anchor, evidenceContent)) return "";
        return String.join(" ", java.util.Arrays.asList(loc.lines()).subList(loc.chapeau(), loc.end() + 1));
    }

    /**
     * Unidade normativa da ocorrência localizada, por posições (parecer R4-02): enunciado introdutório, linhas da âncora e
     * linhas de continuação que se lhes seguem, até à alínea ou número seguintes.
     */
    public record NormativeUnit(List<String> outer, String chapeau, List<String> body, List<String> continuations) {

        public NormativeUnit(String chapeau, List<String> body, List<String> continuations) {
            this(List.of(), chapeau, body, continuations);
        }

        /**
         * Texto oficial completo (KF-PILOT/R-1), citável: o que não for contíguo fica em citações literais separadas por «» […] «», para
         * que nenhuma contenha texto que não existe seguido na evidência. Inclui os enunciados exteriores que regem a unidade.
         */
        public String fullText(boolean chapeauAdjacent) {
            List<String> rest = new ArrayList<>(body);
            rest.addAll(continuations);
            String tail = String.join(" ", rest);
            String head = chapeau.isBlank() ? tail : chapeau + (chapeauAdjacent ? " " : "» […] «") + tail;
            for (int i = outer.size() - 1; i >= 0; i--) head = outer.get(i) + "» […] «" + head;
            return head;
        }
    }

    public static NormativeUnit normativeUnit(String anchor, String evidenceContent) {
        Located loc = locate(anchor, evidenceContent);
        if (loc == null) return new NormativeUnit("", List.of(), List.of());
        List<String> body = new ArrayList<>();
        for (int i = loc.start(); i <= loc.end(); i++) body.add(loc.lines()[i].strip());
        List<String> continuations = new ArrayList<>();
        for (int i = loc.end() + 1; i < loc.lines().length; i++) {
            String line = loc.lines()[i];
            if (line.isBlank()) continue; // uma linha em branco não é fronteira jurídica (parecer R5-03)
            if (!isContinuation(loc.lines(), i)) break;
            continuations.add(line.strip());
        }
        List<String> outer = new ArrayList<>(); // do mais exterior para o mais próximo do enunciado
        if (loc.chapeau() >= 0) {
            for (int k = chapeau(loc.lines(), loc.chapeau()); k >= 0; k = chapeau(loc.lines(), k)) outer.add(0, loc.lines()[k].strip());
        }
        return new NormativeUnit(outer, loc.chapeau() < 0 ? "" : loc.lines()[loc.chapeau()].strip(), body, continuations);
    }

    /** Texto oficial a apresentar para uma âncora: a unidade normativa completa (KF-PILOT/R-1). */
    public static String officialText(String anchor, String evidenceContent) {
        Located loc = locate(anchor, evidenceContent);
        boolean adjacent = loc != null && loc.chapeau() >= 0 && loc.chapeau() == loc.start() - 1;
        return normativeUnit(anchor, evidenceContent).fullText(adjacent);
    }

    /**
     * Qualificações da norma não cobertas por nenhuma das âncoras de condição (KF-M02/ARCH-01).
     * <ol>
     *   <li>cada oração das linhas normativas (âncora e enunciado introdutório) iniciada por um conector
     *       de condição/excepção, até à pontuação seguinte;</li>
     *   <li>cada alínea do mesmo enunciado introdutório (ou do enunciado que a própria âncora é) que
     *       seja uma condição: começa por um conector, ou o enunciado declara as alíneas cumulativas.</li>
     * </ol>
     * Coberta = uma âncora de condição está nessa linha e (no caso 1) sobrepõe-se à oração.
     */
    static List<String> uncoveredQualifiers(String anchor, String evidenceContent, List<String> conditionAnchors) {
        Located loc = locate(anchor, evidenceContent);
        if (loc == null) return List.of();
        String[] lines = loc.lines();
        List<String> anchors = conditionAnchors.stream().filter(c -> c != null && !c.isBlank()).map(SupportVerifier::flat).toList();
        List<String> uncovered = new ArrayList<>();

        List<Integer> normative = new ArrayList<>();
        for (int i = loc.start(); i <= loc.end(); i++) normative.add(i);
        if (loc.chapeau() >= 0) normative.add(loc.chapeau());
        for (int i : normative) {
            String line = flat(lines[i]);
            for (Pattern qualifier : List.of(QUALIFIER_STRICT, QUALIFIER_SE)) {
                var m = qualifier.matcher(line);
                while (m.find()) {
                    var end = CLAUSE_END.matcher(line);
                    int clauseEnd = end.find(m.end()) ? end.start() : line.length();
                    if (!coveredIn(line, m.start(), clauseEnd, anchors)) uncovered.add(line.substring(m.start(), clauseEnd).strip());
                }
            }
        }

        // Alíneas-condição do enunciado introdutório (o da âncora, ou a própria âncora se termina em ":").
        int intro = loc.chapeau() >= 0 ? loc.chapeau() : lines[loc.end()].strip().endsWith(":") ? loc.end() : -1;
        if (intro >= 0) {
            boolean cumulative = flat(lines[intro]).contains("cumulativ");
            for (int k : itemsOf(lines, intro)) {
                if (k >= loc.start() && k <= loc.end()) continue; // a própria âncora: tratada em 1.
                String item = flat(lines[k]);
                String body = ITEM.matcher(item).replaceFirst("").strip();
                boolean isCondition = cumulative || QUALIFIER_STRICT.matcher(body).lookingAt() || QUALIFIER_SE.matcher(body).lookingAt();
                if (isCondition && !coveredIn(item, 0, item.length(), anchors)) uncovered.add(item);
            }
        }
        return List.copyOf(new LinkedHashSet<>(uncovered));
    }

    /** Linhas de alínea que dependem do enunciado {@code intro}: até ao número seguinte ou a uma alínea do mesmo nível do enunciado. */
    private static List<Integer> itemsOf(String[] lines, int intro) {
        var introItem = ITEM.matcher(lines[intro]);
        Boolean introIsDigit = introItem.find() ? Character.isDigit(introItem.group(1).charAt(0)) : null;
        List<Integer> items = new ArrayList<>();
        for (int k = intro + 1; k < lines.length; k++) {
            if (NUMBERED.matcher(lines[k]).find()) break;
            var item = ITEM.matcher(lines[k]);
            if (!item.find()) continue;
            if (introIsDigit != null && Character.isDigit(item.group(1).charAt(0)) == introIsDigit) break;
            items.add(k);
        }
        return items;
    }

    private static boolean coveredIn(String line, int from, int to, List<String> anchors) {
        for (String a : anchors) {
            for (int at = line.indexOf(a); at >= 0; at = line.indexOf(a, at + 1)) {
                if (at < to && at + a.length() > from) return true;
            }
        }
        return false;
    }

    /** Texto sem acentos, minúsculas, hífens/aspas tipográficos e espaços normalizados (posições comparáveis). */
    private static String flat(String text) {
        return fold(AnchorVerifier.normalize(text));
    }

    static Set<String> stems(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        String n = DIACRITICS.matcher(Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)).replaceAll("");
        for (String token : NON_ALPHA.split(n)) {
            if (token.length() < 3 || FUNCTION_WORDS.contains(token)) continue;
            out.add(token.length() > STEM ? token.substring(0, STEM) : token);
        }
        return out;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
