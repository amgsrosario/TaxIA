package com.knowledgeflow.research.source;

import com.knowledgeflow.research.enums.ValidityStatus;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

/**
 * Lê uma página de artigo dos códigos consolidados da AT e confirma que contém o artigo pedido.
 * <p>
 * Só usa o bloco oficial do artigo ({@code #BODYCONTENT}); exclui navegação, scripts, ligações a
 * redacções anteriores ({@code .old_v}) e a lista "Artigo alterado por" ({@code .redAnt*}), que
 * fica apenas como nota de alteração. Nunca completa nem reescreve o texto oficial.
 * <p>
 * Vigência (ADR-010), determinada só por sinais explícitos da página:
 * <ul>
 *   <li>{@code REVOKED}: o corpo do artigo é apenas "(Revogado…)";</li>
 *   <li>{@code UNCERTAIN}: uma nota de alteração refere um ano posterior ao da consulta
 *       (alteração possivelmente ainda não em vigor ou não consolidada);</li>
 *   <li>{@code CONSOLIDATED_AT_RETRIEVAL}: caso contrário — versão consolidada publicada pela AT
 *       à data da consulta, sem verificação humana.</li>
 * </ul>
 */
@Component
public class ConsolidatedArticleParser {

    private static final Pattern HEADING = Pattern.compile("(?iu)^Artigo\\s+(\\d{1,3})\\.?\\s?[ºo°](?:\\s*-\\s*([A-Z]))?\\s*$");
    private static final String NOTE_START = "(?:Reda[cç]{1,2}[ãa]o|Aditad[oa]|Revogad[oa] pel|Anterior|Rectifica|Retifica|Renumerad)";
    private static final Pattern NOTE = Pattern.compile("(?iu)^\\(" + NOTE_START + "[^)]*\\)\\.?$");
    /** Nota de redacção no fim de uma linha de texto: "… alíneas: (Redação da Lei n.º 82/2023, …)". */
    private static final Pattern INLINE_NOTE = Pattern.compile("(?iu)\\s*\\(" + NOTE_START + "[^()]*\\)\\.?");
    private static final Pattern REVOKED_ITEM = Pattern.compile("(?iu)^(\\d+|[a-z]\\)|\\d+\\))\\s*-?\\s*\\(Revogad[oa]\\.?\\)\\.?$");
    private static final Pattern WHOLE_ARTICLE_REVOKED = Pattern.compile("(?iu)^\\(?Revogad[oa]\\.?\\)?\\.?$");
    private static final Pattern YEAR = Pattern.compile("\\b(19|20)\\d{2}\\b");
    private static final Pattern INVISIBLE = Pattern.compile("[\\u200B\\u200C\\u200D\\uFEFF\\u00AD]");

    /** Resultado da leitura. {@code confirmed=false}: a página não contém o artigo pedido. */
    public record ParsedArticle(
            boolean confirmed,
            String heading,
            String epigraph,
            String content,
            List<String> amendmentNotes,
            List<String> revokedItems,
            ValidityStatus validityStatus,
            String validityReason,
            String detail) {

        static ParsedArticle notConfirmed(String detail) {
            return new ParsedArticle(false, null, null, null, List.of(), List.of(),
                    ValidityStatus.UNCERTAIN, detail, detail);
        }
    }

    public ParsedArticle parse(String html, LegislationReference expected, LocalDate retrievalDate) {
        if (html == null || html.isBlank()) return ParsedArticle.notConfirmed("Página vazia");
        Document doc = Jsoup.parse(html);
        Element body = doc.selectFirst("#BODYCONTENT");
        if (body == null) return ParsedArticle.notConfirmed("Bloco oficial do artigo (#BODYCONTENT) não encontrado");

        List<String> amendedBy = new ArrayList<>();
        for (Element block : doc.select(".redAnt0, .redAnt1")) {
            for (String line : lines(block)) {
                if (!line.startsWith("[+ info]")) amendedBy.add(line);
            }
            block.remove();
        }
        doc.select("script, style, img, .old_v").remove();

        List<String> lines = lines(body);
        int headingAt = -1;
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = HEADING.matcher(lines.get(i));
            if (m.matches()) {
                if (matches(expected, m)) {
                    headingAt = i;
                    break;
                }
                return ParsedArticle.notConfirmed(
                        "A página contém '%s' e não o %s".formatted(lines.get(i), expected.display()));
            }
        }
        if (headingAt < 0) {
            return ParsedArticle.notConfirmed("Cabeçalho do " + expected.display() + " não encontrado na página");
        }

        String heading = lines.get(headingAt);
        List<String> rest = lines.subList(headingAt + 1, lines.size());
        String epigraph = rest.isEmpty() || NOTE.matcher(rest.get(0)).matches()
                || WHOLE_ARTICLE_REVOKED.matcher(rest.get(0)).matches() ? null : rest.get(0);
        List<String> bodyLines = epigraph == null ? rest : rest.subList(1, rest.size());

        List<String> notes = new ArrayList<>();
        List<String> revoked = new ArrayList<>();
        List<String> contentLines = new ArrayList<>();
        for (String line : bodyLines) {
            if (WHOLE_ARTICLE_REVOKED.matcher(line).matches()) {
                contentLines.add(line);
                continue;
            }
            if (NOTE.matcher(line).matches()) {
                notes.add(line);
                continue;
            }
            Matcher r = REVOKED_ITEM.matcher(line);
            if (r.matches()) {
                revoked.add(r.group(1));
                contentLines.add(line);
                continue;
            }
            Matcher inline = INLINE_NOTE.matcher(line);
            StringBuilder text = new StringBuilder();
            while (inline.find()) {
                notes.add(inline.group().strip());
                inline.appendReplacement(text, "");
            }
            inline.appendTail(text);
            String cleaned = text.toString().strip();
            if (!cleaned.isEmpty()) contentLines.add(cleaned);
        }
        notes.addAll(amendedBy.stream().map(a -> "Alterado por: " + a).toList());

        if (contentLines.isEmpty()) {
            return ParsedArticle.notConfirmed("O " + expected.display() + " não tem texto na página");
        }

        ValidityStatus validity;
        String reason;
        if (contentLines.size() == 1 && WHOLE_ARTICLE_REVOKED.matcher(contentLines.get(0)).matches()) {
            validity = ValidityStatus.REVOKED;
            reason = "O texto consolidado indica o artigo como revogado";
        } else {
            Integer laterYear = laterYear(notes, retrievalDate.getYear());
            if (laterYear != null) {
                validity = ValidityStatus.UNCERTAIN;
                reason = "Nota de alteração refere %d, posterior ao ano da consulta (%d): vigência a confirmar"
                        .formatted(laterYear, retrievalDate.getYear());
            } else {
                validity = ValidityStatus.CONSOLIDATED_AT_RETRIEVAL;
                reason = "Versão consolidada publicada pela AT no Portal das Finanças, consultada em "
                        + retrievalDate + "; sem verificação humana";
            }
        }

        String content = (epigraph == null ? "" : epigraph + "\n") + String.join("\n", contentLines);
        return new ParsedArticle(true, heading, epigraph, content, List.copyOf(notes), List.copyOf(revoked),
                validity, reason, null);
    }

    private static boolean matches(LegislationReference expected, Matcher heading) {
        int article = Integer.parseInt(heading.group(1));
        String suffix = heading.group(2) == null ? null : heading.group(2).toUpperCase(Locale.ROOT);
        return article == expected.article()
                && (suffix == null ? expected.suffix() == null : suffix.equals(expected.suffix()));
    }

    private static Integer laterYear(List<String> notes, int retrievalYear) {
        Integer max = null;
        for (String note : notes) {
            Matcher m = YEAR.matcher(note);
            while (m.find()) {
                int year = Integer.parseInt(m.group());
                if (year > retrievalYear && (max == null || year > max)) max = year;
            }
        }
        return max;
    }

    /** Texto do elemento em linhas, quebrando em blocos e {@code <br>}; remove caracteres invisíveis. */
    static List<String> lines(Element root) {
        StringBuilder sb = new StringBuilder();
        appendText(root, sb);
        List<String> out = new ArrayList<>();
        for (String raw : sb.toString().split("\n")) {
            String line = INVISIBLE.matcher(raw).replaceAll("").replace(' ', ' ').replaceAll("\\s+", " ").strip();
            if (!line.isEmpty()) out.add(line);
        }
        return out;
    }

    private static void appendText(Node node, StringBuilder sb) {
        if (node instanceof TextNode text) {
            sb.append(text.getWholeText().replace('\n', ' '));
            return;
        }
        if (node instanceof Element el) {
            boolean block = el.isBlock() || el.normalName().equals("br") || el.normalName().equals("tr")
                    || el.normalName().equals("li");
            if (block) sb.append('\n');
            for (Node child : el.childNodes()) appendText(child, sb);
            if (block) sb.append('\n');
        }
    }
}
