package com.knowledgeflow.ai.grounding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic equivalence for the textual sensitive claims (deductibility, obligation,
 * exemption) of {@link AnswerGroundingValidator}.
 *
 * <p>A textual claim is supported only by context evidence of the <b>same family</b> and the
 * <b>same polarity</b>. Polarity has three values:
 * <ul>
 *   <li>{@code POSITIVE}: no negator in the preceding window;</li>
 *   <li>{@code NEGATIVE}: a negator ("não", "nem", "nunca", "jamais") directly before the
 *       formulation, optionally with up to two clitic pronouns ("não o pode", "não se lhe pode");</li>
 *   <li>{@code UNCERTAIN}: any other negator or limiter earlier in the same sentence
 *       ("não é, contudo, …", "nem sempre", "nenhuma das despesas … é", "não residentes").
 *       Never used for equivalence; only the pre-existing exact rule (same phrase, context
 *       occurrence not NEGATIVE) can support or be supported by it.</li>
 * </ul>
 * Equivalent formulations are recognised only on the evidence side; answers are checked with
 * exactly the formulations the validator already detected. No semantic, fuzzy, embedding or LLM
 * matching. Isolated nouns ("dedução", "obrigação", "isenção") are never evidence.
 *
 * <p>Families:
 * <ul>
 *   <li>{@code DEDUCTIBILITY}: "pode deduzir" ≡ "permite deduzir" ≡ "pode(m) ser deduzido(s)"
 *       ≡ "é dedutível" ≡ "tem direito à dedução" (object not compared, as before). "é deduzido"
 *       (retention/mechanics) and exclusions ("exclui", "excluído") are not evidence.</li>
 *   <li>{@code OBLIGATION}: action-aware — same verb (with "-se" and the object "IVA" when
 *       present), from "é obrigatório V", "está obrigado a V", "deve(m) V", "tem/têm de V";
 *       auxiliaries (ser, ter, haver, estar…) are never an action. The old exact core phrase
 *       ("é obrigatório", number/gender collapsed) also supports. "não deve …" is a prohibition:
 *       its own exact family, never "not obliged".</li>
 *   <li>{@code PROHIBITION}, {@code RENUNCIATION}, {@code DISPENSATION}, {@code NON_SUBJECTION}:
 *       exact canonical phrase only.</li>
 *   <li>{@code EXEMPTION}: "está isento" ≡ "é/são/fica(m) isento(s)". Non-subjection ("não está
 *       sujeito") is a different family and never equals exemption.</li>
 * </ul>
 */
final class TextualClaimEquivalence {

    enum Family {
        DEDUCTIBILITY(SensitiveClaimType.DEDUCTIBILITY, Mode.FAMILY),
        OBLIGATION(SensitiveClaimType.LEGAL_OBLIGATION, Mode.ACTION),
        MUST_NOT(SensitiveClaimType.LEGAL_OBLIGATION, Mode.ACTION),
        PROHIBITION(SensitiveClaimType.LEGAL_OBLIGATION, Mode.EXACT),
        RENUNCIATION(SensitiveClaimType.LEGAL_OBLIGATION, Mode.EXACT),
        EXEMPTION(SensitiveClaimType.EXEMPTION, Mode.FAMILY),
        NON_SUBJECTION(SensitiveClaimType.EXEMPTION, Mode.EXACT),
        DISPENSATION(SensitiveClaimType.EXEMPTION, Mode.EXACT);

        final SensitiveClaimType type;
        final Mode mode;

        Family(SensitiveClaimType type, Mode mode) {
            this.type = type;
            this.mode = mode;
        }
    }

    enum Mode { FAMILY, ACTION, EXACT }

    enum Polarity { POSITIVE, NEGATIVE, UNCERTAIN }

    /**
     * A detected claim or a piece of context evidence. {@code canonical}: number/gender-neutral
     * core phrase (clear polarities only); {@code action}: verb key, or null; {@code literal}: the
     * core phrase as written (the pre-existing exact rule).
     */
    record Statement(Family family, Polarity polarity, String text, String canonical, String action,
                     String literal) {
    }

    /**
     * {@code onNegation}: family a NEGATIVE occurrence belongs to (e.g. "não deve …" → MUST_NOT);
     * {@code requiresNegation}: only NEGATIVE occurrences count; {@code actionGroup}: first group
     * holding a verb (0 none); {@code fullPhraseCanonical}: the exact-phrase fallback compares the
     * whole phrase ("deve liquidar iva"), not just the core ("deve").
     */
    private record Form(Family family, Pattern pattern, Family onNegation, boolean requiresNegation,
                        int actionGroup, boolean fullPhraseCanonical) {

        Form(Family family, Pattern pattern, Family onNegation, boolean requiresNegation, int actionGroup) {
            this(family, pattern, onNegation, requiresNegation, actionGroup, false);
        }
    }

    private static final String INF = "(\\p{L}+(?:ar|er|ir|or|ôr)(?:-se)?)";
    private static final String INF_PAIR = INF + "(?:\\s+e\\s+" + INF + ")?";

    /** Auxiliary/light verbs: never an obligation action ("deve ter em atenção", "devem ser analisadas"). */
    private static final Set<String> AUXILIARIES = Set.of(
            "ser", "ter", "haver", "estar", "poder", "ficar", "ir", "vir", "fazer", "dar", "dever", "ver", "por", "pôr",
            "proceder", "efetuar", "efectuar", "entender", "considerar", "observar", "tomar");

    private static Pattern ev(String regex) {
        return Pattern.compile("(?iu)(?<!\\p{L})(?:" + regex + ")(?!\\p{L})");
    }

    /** Same answer formulations as before this change (same case sensitivity). */
    private static Pattern cl(String regex) {
        return Pattern.compile("(?u)(?<!\\p{L})(?:" + regex + ")(?!\\p{L})");
    }

    private static final List<Form> CLAIM_FORMS = List.of(
            new Form(Family.DEDUCTIBILITY, cl("é\\s+dedutível|são\\s+dedutíveis?|tem\\s+direito\\s+à\\s+dedução"
                    + "|têm\\s+direito\\s+à\\s+dedução|pode[m]?\\s+deduzir"), Family.DEDUCTIBILITY, false, 0),
            new Form(Family.OBLIGATION, cl("(?:é|são)\\s+obrigatóri[oa][s]?(?:\\s+(?:o\\s+|a\\s+)?" + INF + ")?"),
                    Family.OBLIGATION, false, 1),
            new Form(Family.OBLIGATION, cl("est(?:á|ão)\\s+obrigad[ao]s?\\s+a(?:\\s+" + INF + ")?"),
                    Family.OBLIGATION, false, 1),
            new Form(Family.OBLIGATION, cl("deve\\s+(liquidar)\\s+(?:o\\s+)?[Ii][Vv][Aa]|deve\\s+(declarar)\\s+(?:o\\s+)?[Ii][Vv][Aa]"
                    + "|deve\\s+(registar)[\\-\\s]?se"), Family.MUST_NOT, false, 1, true),
            new Form(Family.PROHIBITION, cl("é\\s+proibid[ao]"), Family.PROHIBITION, false, 0),
            new Form(Family.RENUNCIATION, cl("pode\\s+renunciar"), Family.RENUNCIATION, false, 0),
            new Form(Family.EXEMPTION, cl("est(?:á|ão)\\s+isent[ao]s?"), Family.EXEMPTION, false, 0),
            new Form(Family.DISPENSATION, cl("está\\s+dispensad[ao]"), Family.DISPENSATION, false, 0),
            new Form(Family.NON_SUBJECTION, cl("est(?:á|ão)\\s+sujeit[oa]s?"), Family.NON_SUBJECTION, true, 0)
    );

    private static final List<Form> EVIDENCE_FORMS = List.of(
            new Form(Family.DEDUCTIBILITY, ev("pode(?:m)?\\s+deduzir|permite(?:m)?\\s+deduzir"
                    + "|pode(?:m)?\\s+ser\\s+deduzid[oa]s?|é\\s+dedutível|são\\s+dedutíveis"
                    + "|tem\\s+direito\\s+à\\s+dedução|têm\\s+direito\\s+à\\s+dedução"), Family.DEDUCTIBILITY, false, 0),
            new Form(Family.OBLIGATION, ev("(?:é|são)\\s+obrigatóri[oa]s?(?:\\s+(?:o\\s+|a\\s+)?" + INF + ")?"),
                    Family.OBLIGATION, false, 1),
            new Form(Family.OBLIGATION, ev("(?:está|estão|fica|ficam)\\s+obrigad[oa]s?\\s+a(?:\\s+" + INF + ")?"),
                    Family.OBLIGATION, false, 1),
            new Form(Family.OBLIGATION, ev("deve(?:m)?\\s+(?:ainda\\s+|também\\s+|sempre\\s+)?" + INF_PAIR),
                    Family.MUST_NOT, false, 1, true),
            new Form(Family.OBLIGATION, ev("(?:tem|têm)\\s+de\\s+" + INF_PAIR), Family.OBLIGATION, false, 1),
            new Form(Family.PROHIBITION, ev("(?:é|são)\\s+proibid[oa]s?"), Family.PROHIBITION, false, 0),
            new Form(Family.RENUNCIATION, ev("pode(?:m)?\\s+renunciar"), Family.RENUNCIATION, false, 0),
            new Form(Family.EXEMPTION, ev("(?:está|estão|é|são|fica|ficam)\\s+isent[oa]s?"), Family.EXEMPTION, false, 0),
            new Form(Family.DISPENSATION, ev("(?:está|estão)\\s+dispensad[oa]s?"), Family.DISPENSATION, false, 0),
            new Form(Family.NON_SUBJECTION, ev("(?:está|estão)\\s+sujeit[oa]s?"), Family.NON_SUBJECTION, true, 0)
    );

    private static final String CLITIC = "(?:se|lhe|lhes|o|a|os|as|lo|la|los|las|no|na|nos|nas)";

    /** Negator directly before the formulation (up to two clitics): NEGATIVE. */
    private static final Pattern NEGATOR_ATTACHED = Pattern.compile(
            "(?iu)(?<!\\p{L})(?:não|nem|nunca|jamais)\\s+(?:" + CLITIC + "\\s+){0,2}$");

    /** Any negator/limiter earlier in the same sentence (not attached): UNCERTAIN. */
    private static final Pattern NEGATOR_NEARBY = Pattern.compile(
            "(?iu)(?<!\\p{L})(?:não|nem|nunca|jamais|nenhum|nenhuma|nada|caso\\s+algum|sem)(?!\\p{L})");

    /** Abbreviations whose dot does not end a sentence ("art. 41.º", "n.º 1", "cf. …"). */
    private static final Pattern ABBREVIATION_BEFORE_DOT =
            Pattern.compile("(?iu)(?<!\\p{L})(?:art|arts|n|al|als|cf|p|pp|ex|nº|n\\.º)$");

    private TextualClaimEquivalence() {
    }

    static List<Statement> claims(String answer) {
        return scan(answer, CLAIM_FORMS);
    }

    static List<Statement> evidence(String context) {
        return scan(context, EVIDENCE_FORMS);
    }

    /**
     * Whether the context supports the claim.
     * <ul>
     *   <li>POSITIVE or UNCERTAIN claim — pre-existing exact rule, as before: the literal phrase
     *       occurs in the context, in an occurrence that is not NEGATIVE (the only change: negative
     *       context no longer supports a positive claim).</li>
     *   <li>POSITIVE claim — equivalence with POSITIVE evidence of the same family (by mode).</li>
     *   <li>NEGATIVE claim — equivalence with NEGATIVE evidence of the same family (by mode).</li>
     * </ul>
     */
    static boolean supports(Statement claim, List<Statement> evidence, String contextText) {
        if (claim.polarity() != Polarity.NEGATIVE && literalOccursNotNegated(claim.literal(), contextText)) {
            return true;
        }
        if (claim.polarity() == Polarity.UNCERTAIN) {
            return false;
        }
        for (Statement e : evidence) {
            if (e.family() != claim.family() || e.polarity() != claim.polarity()) {
                continue;
            }
            switch (claim.family().mode) {
                case FAMILY -> {
                    return true;
                }
                case EXACT -> {
                    if (e.canonical().equals(claim.canonical())) return true;
                }
                case ACTION -> {
                    if ((claim.action() != null && claim.action().equals(e.action()))
                            || e.canonical().equals(claim.canonical())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The pre-existing exact rule, restricted to occurrences whose polarity is not NEGATIVE. */
    private static boolean literalOccursNotNegated(String literal, String rawContext) {
        if (literal == null || literal.isBlank() || rawContext == null) {
            return false;
        }
        String text = rawContext.replaceAll("[\\u00A0\\u202F]", " ");
        String regex = Pattern.quote(literal).replace(" ", "\\E[\\s\\-]+\\Q");
        Matcher m = Pattern.compile("(?iu)(?<!\\p{L})" + regex + "(?!\\p{L})").matcher(text);
        while (m.find()) {
            String clause = text.substring(sentenceStart(text, m.start()), m.start());
            if (!NEGATOR_ATTACHED.matcher(clause).find()) {
                return true;
            }
        }
        return false;
    }

    private static List<Statement> scan(String raw, List<Form> forms) {
        List<Statement> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String text = raw.replaceAll("[\\u00A0\\u202F]", " ");
        for (Form form : forms) {
            Matcher m = form.pattern().matcher(text);
            while (m.find()) {
                // Polarity scope: the whole current sentence up to the formulation.
                String clause = text.substring(sentenceStart(text, m.start()), m.start());
                Matcher attached = NEGATOR_ATTACHED.matcher(clause);
                Polarity polarity = attached.find() ? Polarity.NEGATIVE
                        : NEGATOR_NEARBY.matcher(clause).find() ? Polarity.UNCERTAIN
                        : Polarity.POSITIVE;
                if (form.requiresNegation() && polarity != Polarity.NEGATIVE) continue;
                Family family = polarity == Polarity.NEGATIVE ? form.onNegation() : form.family();
                String phrase = m.group();
                String shown = polarity == Polarity.NEGATIVE
                        ? clause.substring(attached.start()).strip() + " " + phrase : phrase;
                int coreEnd = !form.fullPhraseCanonical() && form.actionGroup() > 0
                        && m.group(form.actionGroup()) != null ? m.start(form.actionGroup()) : m.end();
                String core = text.substring(m.start(), coreEnd).replaceAll("(?iu)\\s+(?:o|a)?\\s*$", "");
                // Literal for the pre-existing exact rule: as the old patterns matched it ("está obrigado a"
                // keeps its "a"; "é obrigatório o/a" drops the article before the verb).
                String asWritten = text.substring(m.start(), coreEnd).strip();
                String literal = normalize(asWritten.matches("(?iu).*obrigad[oa]s?\\s+a$") ? asWritten : core).replace('-', ' ');
                for (String action : actions(form, m, text)) {
                    // "deve" forms: canonical = "deve " + action key (verb, "-se", object "iva").
                    String canonical = form.fullPhraseCanonical() && action != null
                            ? "deve " + action : canonical(core);
                    out.add(new Statement(family, polarity, shown.strip(), canonical, action, literal));
                }
            }
        }
        return out;
    }

    /**
     * Start of the sentence containing {@code position}: after the last ".", "!", "?", ";" or line
     * break that ends a sentence. A dot is not a sentence end when followed by a digit, "º" or "ª"
     * ("41.º", "1.000") or preceded by an abbreviation ("art.", "n.", "cf.").
     */
    static int sentenceStart(String text, int position) {
        for (int i = position - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '!' || c == '?' || c == ';' || c == '\n') {
                return i + 1;
            }
            if (c == '.') {
                char next = i + 1 < text.length() ? text.charAt(i + 1) : ' ';
                if (Character.isDigit(next) || next == 'º' || next == 'ª') continue;
                if (ABBREVIATION_BEFORE_DOT.matcher(text.substring(Math.max(0, i - 4), i)).find()) continue;
                return i + 1;
            }
        }
        return 0;
    }

    /** Verb keys governed by the match: verb (with "-se") plus " iva" when "(o) IVA" follows. */
    private static List<String> actions(Form form, Matcher m, String text) {
        List<String> verbs = new ArrayList<>();
        if (form.actionGroup() > 0) {
            for (int g = form.actionGroup(); g <= m.groupCount(); g++) {
                String verb = m.group(g);
                if (verb == null) continue;
                String v = verb.toLowerCase(Locale.ROOT);
                if (AUXILIARIES.contains(v.replace("-se", ""))) continue;
                String after = text.substring(m.end(g), Math.min(text.length(), m.end(g) + 8));
                boolean reflexive = v.endsWith("-se") || after.matches("(?s)^[\\-\\s]se(?!\\p{L}).*");
                boolean iva = after.matches("(?iu)(?s)^\\s+(?:o\\s+)?iva(?!\\p{L}).*");
                verbs.add(v.replace("-se", "") + (reflexive ? "-se" : "") + (iva ? " iva" : ""));
            }
        }
        if (verbs.isEmpty()) verbs.add(null);
        return verbs;
    }

    /** Lower case, no-break spaces and runs of whitespace collapsed. */
    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\u00A0\\u202F]", " ").replaceAll("\\s+", " ").strip();
    }

    /** Number/gender-neutral canonical core phrase (EXACT and ACTION fallback comparison). */
    static String canonical(String phrase) {
        return normalize(phrase)
                .replaceAll("(?<!\\p{L})são(?!\\p{L})", "é")
                .replaceAll("(?<!\\p{L})estão(?!\\p{L})", "está")
                .replaceAll("(?<!\\p{L})ficam(?!\\p{L})", "fica")
                .replaceAll("(?<!\\p{L})podem(?!\\p{L})", "pode")
                .replaceAll("(?<!\\p{L})devem(?!\\p{L})", "deve")
                .replaceAll("(?<!\\p{L})têm(?!\\p{L})", "tem")
                .replaceAll("[\\-\\s]se(?!\\p{L})", "-se")
                .replaceAll("(?<!\\p{L})o iva(?!\\p{L})", "iva")
                .replaceAll("obrigatóri[oa]s?", "obrigatório")
                .replaceAll("obrigad[oa]s?", "obrigado")
                .replaceAll("proibid[oa]s?", "proibido")
                .replaceAll("isent[oa]s?", "isento")
                .replaceAll("sujeit[oa]s?", "sujeito")
                .replaceAll("dispensad[oa]s?", "dispensado")
                .replaceAll("dedutíveis?", "dedutível");
    }
}
