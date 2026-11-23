package com.knowledgeflow.research.service;

import com.knowledgeflow.research.enums.EntailmentDecision;
import com.knowledgeflow.research.service.EntailmentValidator.Assessment;
import com.knowledgeflow.research.service.EntailmentValidator.Dimension;
import com.knowledgeflow.research.service.EntailmentValidator.DimensionStatus;
import com.knowledgeflow.research.service.EntailmentValidator.Verdict;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import org.springframework.stereotype.Component;

/**
 * Decisão final, determinística, da segunda barreira (KF-M02/ARCH-01). A implicação só fica
 * DEMONSTRADA quando, cumulativamente:
 * <ol>
 *   <li>a resposta respeita o protocolo (JSON único, sem chaves duplicadas, sem veredicto fora do JSON);</li>
 *   <li>o veredicto é {@code SUPORTA};</li>
 *   <li>as sete dimensões foram avaliadas; sujeito, predicado e polaridade estão {@code CONFORME} e as
 *       restantes {@code CONFORME} ou {@code NAO_APLICAVEL} — {@code NAO_CONFORME} com {@code SUPORTA} é
 *       contradição e {@code INDETERMINADO} é incerteza; numa unidade revogada, o âmbito temporal também
 *       tem de estar {@code CONFORME} (KF-M02/CORR-11);</li>
 *   <li>há justificação;</li>
 *   <li>a passagem citada existe literalmente na evidência oficial ({@link AnchorVerifier}) e pertence à
 *       unidade legal da proposição.</li>
 * </ol>
 * Qualquer outro caso — incluindo barreira desligada, limite de validações ou falha da chamada — é
 * suporte não demonstrado. Esta decisão só é tomada sobre proposições que as verificações
 * determinísticas não rejeitaram: nunca converte uma rejeição em aprovação.
 */
@Component
public class EntailmentGate {

    private static final Set<Dimension> ESSENTIAL = EnumSet.of(Dimension.SUJEITO, Dimension.PREDICADO, Dimension.POLARIDADE);

    private static final int MIN_OVERLAP = 12;

    private final AnchorVerifier anchors = new AnchorVerifier();

    public record Decision(EntailmentDecision decision, String reason, boolean citationVerified) {
        public boolean demonstrated() {
            return decision == EntailmentDecision.DEMONSTRATED;
        }
    }

    public static Decision notDemonstrated(String reason) {
        return new Decision(EntailmentDecision.NOT_DEMONSTRATED, reason, false);
    }

    /**
     * @param legalUnit unidade legal da âncora da proposição ({@link SupportVerifier#unitContext})
     */
    public Decision decide(Assessment a, String statement, String evidenceContent, String legalUnit) {
        return decide(a, statement, evidenceContent, legalUnit, "", null, null);
    }

    /**
     * @param contiguousUnit enunciado e alínea pela ordem do documento ({@link SupportVerifier#contiguousUnit}), ou vazio
     * @param unit unidade normativa da ocorrência localizada, para a verificação do contexto
     * @param conditionAnchors âncoras literais das condições estruturadas da proposição
     */
    public Decision decide(Assessment a, String statement, String evidenceContent, String legalUnit, String contiguousUnit,
            SupportVerifier.NormativeUnit unit, List<String> conditionAnchors) {
        if (a == null) return notDemonstrated("segunda barreira sem resultado");
        boolean cited = citationVerified(a.citedPassage(), statement, evidenceContent, legalUnit, contiguousUnit);
        if (a.protocolError() != null) return no("resposta fora do protocolo: " + a.protocolError(), cited);
        if (a.verdict() == null) return no("sem veredicto", cited);
        if (a.verdict() == Verdict.NAO_SUPORTA) return no("veredicto NAO_SUPORTA", cited);
        if (a.verdict() == Verdict.INDETERMINADO) return no("veredicto INDETERMINADO (incerteza)", cited);

        Map<Dimension, DimensionStatus> dims = a.dimensions() == null ? Map.of() : a.dimensions();
        List<String> missing = new ArrayList<>();
        List<String> contrary = new ArrayList<>();
        List<String> uncertain = new ArrayList<>();
        List<String> essentialNotApplicable = new ArrayList<>();
        // KF-M02/CORR-11: numa unidade revogada o âmbito temporal é a questão — não pode ser declarado não aplicável.
        boolean revokedUnit = isRevokedUnit(legalUnit);
        for (Dimension d : Dimension.values()) {
            DimensionStatus s = dims.get(d);
            if (s == null) missing.add(d.name());
            else if (s == DimensionStatus.NAO_CONFORME) contrary.add(d.name());
            else if (s == DimensionStatus.INDETERMINADO) uncertain.add(d.name());
            else if (s == DimensionStatus.NAO_APLICAVEL
                    && (ESSENTIAL.contains(d) || (revokedUnit && d == Dimension.AMBITO_TEMPORAL))) {
                essentialNotApplicable.add(d.name());
            }
        }
        if (!missing.isEmpty()) return no("dimensões não avaliadas: " + String.join(", ", missing), cited);
        if (!contrary.isEmpty()) return no("contradição: SUPORTA com dimensões NAO_CONFORME: " + String.join(", ", contrary), cited);
        if (!uncertain.isEmpty()) return no("incerteza nas dimensões: " + String.join(", ", uncertain), cited);
        if (!essentialNotApplicable.isEmpty()) {
            return no("dimensões essenciais declaradas não aplicáveis: " + String.join(", ", essentialNotApplicable), cited);
        }
        if (a.justification() == null || a.justification().isBlank()) return no("sem justificação", cited);
        if (a.citedPassage() == null || a.citedPassage().isBlank()) return no("sem passagem legal citada", cited);
        if (!cited) return no("passagem citada inexistente na evidência oficial ou fora da unidade legal da proposição", false);
        if (unit != null && !keepsNormativeContext(a.citedPassage(), unit, conditionAnchors)) {
            return no("passagem citada retira uma negação, excepção ou condição da unidade legal", false);
        }
        return new Decision(EntailmentDecision.DEMONSTRATED,
                "veredicto SUPORTA, sete dimensões conformes e passagem citada verificada na evidência oficial", true);
    }

    /** A passagem citada existe literalmente na evidência e está contida na unidade legal da proposição. */
    public boolean citationVerified(String citedPassage, String statement, String evidenceContent, String legalUnit) {
        return citationVerified(citedPassage, statement, evidenceContent, legalUnit, "");
    }

    /**
     * Como acima, aceitando também enunciado + alínea da ocorrência localizada ({@link SupportVerifier#contiguousUnit}), mas só
     * a partir do início do enunciado: um recorte que começasse depois de «Não» retiraria a negação (KF-PILOT/IT-2, parecer R3-03).
     */
    public boolean citationVerified(String citedPassage, String statement, String evidenceContent, String legalUnit,
            String contiguousUnit) {
        if (citedPassage == null || citedPassage.isBlank() || legalUnit == null || legalUnit.isBlank()) return false;
        if (!anchors.isAnchored(citedPassage, evidenceContent, statement)) return false;
        String cited = AnchorVerifier.normalize(citedPassage);
        if (AnchorVerifier.normalize(legalUnit.replace('\n', ' ')).contains(cited)) return true;
        if (contiguousUnit == null || contiguousUnit.isBlank()) return false;
        String unit = AnchorVerifier.normalize(contiguousUnit);
        return unit.startsWith(cited) || unit.replaceFirst("^\\d+(\\.º)?\\s*[-–]\\s*", "").startsWith(cited);
    }

    /**
     * A citação não retira contexto que altera o sentido da norma (pareceres R3-03, R4-02 e R5-02). Cada negação ou excepção do
     * enunciado, das continuações e das linhas tocadas pela citação tem de estar coberta com a sua oração completa, até à
     * pontuação seguinte: pelo troço citado, ou pela âncora literal de uma condição estruturada da mesma linha. A comparação é
     * por posições: uma frase noutro sítio, ou só o conector, não serve.
     * <p>
     * A lista de marcas é necessariamente incompleta. Isto recusa recortes conhecidos; não demonstra a preservação do sentido, que
     * continua a depender das verificações de polaridade e qualificações e da avaliação da barreira.
     */
    static boolean keepsNormativeContext(String citedPassage, SupportVerifier.NormativeUnit unit, List<String> conditionAnchors) {
        String cited = AnchorVerifier.normalize(citedPassage);
        List<String> anchorsNormalized = conditionAnchors == null ? List.of() : conditionAnchors.stream().map(AnchorVerifier::normalize).toList();
        List<String> mandatory = new ArrayList<>(unit.continuations());
        if (!unit.chapeau().isBlank()) mandatory.add(unit.chapeau());
        for (String raw : mandatory) {
            if (!covered(AnchorVerifier.normalize(raw), cited, anchorsNormalized, true)) return false;
        }
        for (String raw : unit.body()) {
            if (!covered(AnchorVerifier.normalize(raw), cited, anchorsNormalized, false)) return false;
        }
        return true;
    }

    private static boolean covered(String line, String cited, List<String> anchors, boolean mandatory) {
        int[] citedSpan = span(cited, line);
        if (citedSpan == null && !mandatory) return true;
        Matcher m = LegalContextLexicon.MARKER.matcher(line);
        while (m.find()) {
            int clauseEnd = clauseEnd(line, m.end());
            if (citedSpan != null && citedSpan[0] <= m.start() && clauseEnd <= citedSpan[1]) continue;
            boolean inAnchor = false;
            for (String anchor : anchors) {
                int i = anchor.isEmpty() ? -1 : line.indexOf(anchor);
                if (i >= 0 && i <= m.start() && clauseEnd <= i + anchor.length()) inAnchor = true;
            }
            if (!inAnchor) return false;
        }
        return true;
    }

    /** Fim da oração da marca: a pontuação seguinte, sem a incluir, ou o fim da linha. */
    private static int clauseEnd(String line, int from) {
        for (int i = from; i < line.length(); i++) {
            if (",;:.".indexOf(line.charAt(i)) >= 0) return i;
        }
        return line.length();
    }

    /** Troço da linha coberto pela citação (posições na linha), ou {@code null} se a citação não a tocar. */
    private static int[] span(String cited, String line) {
        if (cited.contains(line)) return new int[] {0, line.length()};
        int i = line.indexOf(cited);
        if (i >= 0) return new int[] {i, i + cited.length()};
        for (int n = Math.min(cited.length(), line.length()); n >= MIN_OVERLAP; n--) {
            if (cited.regionMatches(cited.length() - n, line, 0, n)) return new int[] {0, n};
            if (line.regionMatches(line.length() - n, cited, 0, n)) return new int[] {line.length() - n, line.length()};
        }
        return null;
    }

    /** A unidade legal (sem a epígrafe) contém uma marca de revogação («4 - (Revogado.)»). */
    static boolean isRevokedUnit(String legalUnit) {
        if (legalUnit == null) return false;
        String[] lines = legalUnit.split("\n");
        for (int i = 1; i < lines.length; i++) if (SupportVerifier.isRevocationMarker(lines[i])) return true;
        return false;
    }

    private static Decision no(String reason, boolean cited) {
        return new Decision(EntailmentDecision.NOT_DEMONSTRATED, reason, cited);
    }
}
