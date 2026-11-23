package com.knowledgeflow.research.source;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Catálogo dos códigos tributários consolidados publicados pela AT no Portal das Finanças
 * (B2: legislação oficial em versão consolidada e informação oficial da AT).
 * <p>
 * Não é um crawler: só constrói o URL do artigo pedido segundo o padrão oficial verificado
 * (ex.: {@code cirs_rep/Pages/irs78a.aspx}). Códigos fora do catálogo não são consultados.
 */
@Component
public class OfficialLegislationCatalog {

    /** code → (pasta, prefixo da página). Padrões verificados por consulta read-only em 2026-10-22. */
    /**
     * code → (pasta, prefixo da página, identificação do imposto/diploma). A identificação (textos do ramo do piloto
     * {@code ac84c04}) alimenta a verificação de suporte substantivo da barreira de implicação. O CIRC fica <b>sem</b>
     * identificação: o piloto retirou-o (KF-M02/CORR-03) por as páginas da AT conterem uma redacção histórica, e a
     * retirada ou alteração do CIRC no {@code main} não está autorizada (deliberação de 23/11/2026, ponto 3). Com a
     * barreira ligada, uma proposição sem identificação é «não demonstrada» (delimitação v3, A.3).
     */
    private static final Map<String, String[]> CODES = Map.of(
            "CIRS", new String[] {"cirs_rep", "irs", "IRS imposto sobre o rendimento das pessoas singulares"},
            "CIVA", new String[] {"civa_rep", "iva", "IVA imposto sobre o valor acrescentado"},
            "CIRC", new String[] {"circ", "irc", ""},
            "LGT", new String[] {"lgt", "lgt", "LGT lei geral tributária"});

    private static final Map<String, String> CODE_ALIASES = Map.of(
            "CIRS", "CIRS", "CÓDIGO DO IRS", "CIRS", "CODIGO DO IRS", "CIRS",
            "CIVA", "CIVA", "CÓDIGO DO IVA", "CIVA", "CODIGO DO IVA", "CIVA",
            "CIRC", "CIRC", "CÓDIGO DO IRC", "CIRC", "CODIGO DO IRC", "CIRC",
            "LGT", "LGT");

    private static final Pattern ARTICLE_WITH_CODE = Pattern.compile(
            "(?iu)\\bart(?:igo|\\.)?\\s*(\\d{1,3})\\.?\\s?[ºo°]?(?:-([A-Z]))?\\s*,?\\s*(?:(?:n\\.?\\s?[ºo°]\\s*\\d+[^,;]*?)\\s*)?"
                    + "(?:do|da|,)?\\s*(CIRS|CIVA|CIRC|LGT|C[óo]digo do (?:IRS|IVA|IRC)|Lei Geral Tribut[áa]ria)\\b");

    private final ResearchProperties properties;

    public OfficialLegislationCatalog(ResearchProperties properties) {
        this.properties = properties;
    }

    public boolean supports(String code) {
        return code != null && CODES.containsKey(code.toUpperCase(Locale.ROOT));
    }

    public Set<String> supportedCodes() {
        return CODES.keySet();
    }

    public Optional<String> urlFor(LegislationReference ref) {
        String[] entry = CODES.get(ref.code());
        if (entry == null) return Optional.empty();
        String page = entry[1] + ref.article() + (ref.suffix() == null ? "" : ref.suffix().toLowerCase(Locale.ROOT));
        return Optional.of(properties.getLegislationBaseUrl() + "/" + entry[0] + "/Pages/" + page + ".aspx");
    }

    /**
     * Identificação do imposto/diploma do código, para a verificação de suporte substantivo. Vazio para códigos
     * desconhecidos ou sem identificação verificada (o CIRC). Nunca lança excepção por entrada incompleta.
     */
    public Optional<String> identification(String code) {
        String[] entry = code == null ? null : CODES.get(code.toUpperCase(Locale.ROOT));
        if (entry == null || entry.length < 3 || entry[2] == null || entry[2].isBlank()) return Optional.empty();
        return Optional.of(entry[2]);
    }

    public String authority() {
        return "AT_CONSOLIDATED_CODE";
    }

    /** Referências explícitas a artigos de códigos do catálogo presentes no texto, por ordem. */
    public List<LegislationReference> referencesIn(String text) {
        if (text == null || text.isBlank()) return List.of();
        Set<LegislationReference> found = new LinkedHashSet<>();
        Matcher m = ARTICLE_WITH_CODE.matcher(text);
        while (m.find()) {
            String code = normalizeCode(m.group(3));
            if (code != null && supports(code)) {
                found.add(new LegislationReference(code, Integer.parseInt(m.group(1)), m.group(2)));
            }
        }
        return new ArrayList<>(found);
    }

    static String normalizeCode(String raw) {
        if (raw == null) return null;
        String upper = raw.strip().toUpperCase(Locale.ROOT);
        if (upper.startsWith("LEI GERAL TRIBUT")) return "LGT";
        return CODE_ALIASES.get(upper);
    }
}
