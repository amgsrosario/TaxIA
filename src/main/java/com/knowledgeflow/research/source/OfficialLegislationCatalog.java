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
    private static final Map<String, String[]> CODES = Map.of(
            "CIRS", new String[] {"cirs_rep", "irs"},
            "CIVA", new String[] {"civa_rep", "iva"},
            "CIRC", new String[] {"circ", "irc"},
            "LGT", new String[] {"lgt", "lgt"});

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
