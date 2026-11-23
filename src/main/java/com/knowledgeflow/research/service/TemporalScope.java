package com.knowledgeflow.research.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Âmbito temporal de conclusões fundadas numa marca de revogação (KF-M02/CORR-11).
 * <p>
 * O texto consolidado mostra «(Revogado.)» sem a data nem o diploma revogatório: prova o estado ACTUAL da
 * disposição, não desde quando deixou de vigorar nem o regime dos factos anteriores. Sem evidência
 * temporal (data, diploma revogatório, regime transitório), nenhuma conclusão sobre aplicabilidade,
 * efeitos ou períodos anteriores pode ser fundamentada nessa marca. Não presume datas nem contém factos
 * fiscais: só reconhece a forma da conclusão.
 * <ul>
 *   <li>{@link #exceedsRevocationEvidence(String)} — usada pelo {@link SupportVerifier} sobre a afirmação
 *       de uma proposição ancorada numa marca de revogação: só a revogação no texto consolidado é
 *       sustentada;</li>
 *   <li>{@link #LIMITATION} — limitação expressa dessas respostas;</li>
 *   <li>{@link #restrictedAnswer} (CORR-12) — a resposta final quando só há regras sobre marcas de revogação:
 *       gerada pelo sistema, sem redacção livre do modelo, que não pode acrescentar conclusões sobre eficácia,
 *       direitos, obrigações ou efeitos temporais.</li>
 * </ul>
 * A CORR-11 detectava conclusões absolutas na redacção livre por uma lista de expressões; a CORR-12 retirou-a,
 * porque uma lista não fecha as paráfrases: o formato restrito não admite redacção livre.
 */
public final class TemporalScope {

    private TemporalScope() {}

    /** Limitação expressa de uma resposta fundada numa marca de revogação. */
    public static final String LIMITATION = "Âmbito temporal: a evidência oficial consultada (texto consolidado) indica apenas "
            + "que a disposição está actualmente revogada; não indica a data nem o diploma da revogação. Não é possível "
            + "concluir, com esta evidência, sobre a aplicação da disposição a factos ou períodos anteriores à revogação.";

    /** Cabeçalho e fecho da resposta de formato restrito (KF-M02/CORR-12). */
    public static final String RESTRICTED_HEADER = "Estado actual segundo o texto consolidado:";
    public static final String RESTRICTED_CLOSING = "Recomenda-se a confirmação por profissional qualificado, porque a "
            + "fundamentação assenta em evidência de investigação não promovida.";

    /** Uma regra verificada sobre uma marca de revogação e a evidência oficial em que assenta. */
    public record RevocationItem(String statement, String officialText, String legalReference, String url,
            java.time.LocalDate retrievedOn) {}

    /**
     * Resposta de formato restrito (KF-M02/CORR-12): gerada pelo sistema, sem texto do modelo, só com o que foi
     * verificado — a afirmação da revogação actual (que passou as verificações determinísticas e a segunda
     * barreira), o texto oficial literal e a fonte — e a limitação temporal expressa.
     */
    public static String restrictedAnswer(List<RevocationItem> items) {
        StringBuilder sb = new StringBuilder(RESTRICTED_HEADER).append('\n');
        for (RevocationItem i : items) sb.append(restrictedLine(i)).append('\n');
        return sb.append(LIMITATION).append('\n').append(RESTRICTED_CLOSING).toString();
    }

    public static String restrictedLine(RevocationItem i) {
        String statement = i.statement().strip().replaceAll("[.;:]+$", "");
        return "- %s. Texto oficial: «%s» (%s, %s, consultado em %s).".formatted(statement, i.officialText().strip(),
                i.legalReference(), i.url(), i.retrievedOn());
    }

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    /**
     * Conclusões que uma marca de revogação não sustenta: aplicabilidade, vigência, efeitos, invocação e
     * referências temporais (períodos, datas, anos, desde, anterior, retroactividade).
     */
    private static final Pattern BEYOND_REVOCATION = Pattern.compile(
            "(?<![a-z])(aplic|inaplic|vigor|vigen|vigenc|efeit|eficac|produz|invoc|deix|forca|fundament|valid|"
                    + "desde|anterior|passad|histor|period|data|datas|ano|anos|retroa|futur|sempre|nunca|a partir)"
                    + "|\\b\\d{4}\\b");

    /** A afirmação vai além da revogação actual (aplicabilidade, efeitos ou tempo). */
    public static boolean exceedsRevocationEvidence(String statement) {
        return BEYOND_REVOCATION.matcher(fold(statement)).find();
    }

    static String fold(String text) {
        if (text == null) return "";
        String n = DIACRITICS.matcher(Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)).replaceAll("");
        return n.replaceAll("[*_`>\"«»]", " ").replaceAll("\\s+", " ");
    }
}
