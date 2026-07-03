package com.knowledgeflow.ingestion.atfaq;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

/**
 * Deterministic HTML parsing for the AT FAQ pages (no AI, no JS execution).
 * <p>
 * Structure observed on info.portaldasfinancas.gov.pt (2026-07):
 * <ul>
 *   <li>Index: Bootstrap accordion; each tax area heading is an
 *       {@code a[data-bs-toggle=collapse]} whose target div contains
 *       {@code ul.submenu-itens > li} groups with {@code ul.submenu-itens-sub li a} links.</li>
 *   <li>Category page: {@code div#faqAccordion > div.card}; the question lives in
 *       {@code .card-header a} prefixed with the official FAQ number
 *       ("4583 - Lista I - Verba 2.36 - …"); the answer lives in {@code .card-body}.</li>
 * </ul>
 */
@Component
public class AtFaqHtmlParser {

    /** Bump when the extraction logic changes in a way that affects output. */
    public static final String PARSER_VERSION = "1.0";

    private static final Pattern FAQ_ID_PATTERN = Pattern.compile("^\\s*(\\d{1,6})\\s*[-–—]\\s*(.+)$", Pattern.DOTALL);

    // ── Result types ─────────────────────────────────────────────────────────

    public record ParsedFaq(
            String officialFaqId,
            String question,
            String answer,
            List<String> links,
            String headerRawText) {
    }

    public record ParseFailure(String reason, String snippet) {
    }

    public record CategoryParseResult(
            String pageTitle,
            List<ParsedFaq> faqs,
            List<ParseFailure> failures) {
    }

    public record IndexLink(String groupLabel, String linkLabel, String url) {
    }

    // ── Index page ───────────────────────────────────────────────────────────

    /**
     * Extracts all category links found inside the accordion section whose
     * heading text equals {@code topArea} (e.g. "IVA"). Returns raw links;
     * authorization filtering is the discovery service's job.
     */
    public List<IndexLink> parseIndex(String html, String baseUrl, String topArea) {
        Document doc = Jsoup.parse(html, baseUrl);
        Element areaToggle = doc.select("a[data-bs-toggle=collapse]").stream()
                .filter(a -> normalizeLabel(a.text()).equals(normalizeLabel(topArea)))
                .findFirst()
                .orElse(null);
        if (areaToggle == null) return List.of();

        String targetRef = areaToggle.attr("href");
        if (targetRef.isEmpty()) targetRef = areaToggle.attr("data-bs-target");
        if (!targetRef.startsWith("#")) return List.of();
        Element section = doc.getElementById(targetRef.substring(1));
        if (section == null) return List.of();

        List<IndexLink> links = new ArrayList<>();
        for (Element group : section.select("ul.submenu-itens > li")) {
            String groupLabel = normalizeLabel(group.ownText());
            for (Element a : group.select("ul.submenu-itens-sub li a[href]")) {
                links.add(new IndexLink(groupLabel, normalizeLabel(a.text()), a.absUrl("href")));
            }
        }
        return links;
    }

    // ── Category page ────────────────────────────────────────────────────────

    public CategoryParseResult parseCategoryPage(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        String pageTitle = doc.title();

        Element accordion = doc.getElementById("faqAccordion");
        if (accordion == null) {
            return new CategoryParseResult(pageTitle, List.of(),
                    List.of(new ParseFailure("Estrutura inesperada: div#faqAccordion não encontrado", null)));
        }

        List<ParsedFaq> faqs = new ArrayList<>();
        List<ParseFailure> failures = new ArrayList<>();

        for (Element card : accordion.select("div.card")) {
            Element headerAnchor = card.selectFirst(".card-header a");
            if (headerAnchor == null) {
                failures.add(new ParseFailure("Card sem cabeçalho de pergunta", snippet(card)));
                continue;
            }
            String headerText = headerAnchor.text().strip();
            Matcher idMatcher = FAQ_ID_PATTERN.matcher(headerText);
            if (!idMatcher.matches()) {
                failures.add(new ParseFailure(
                        "FAQ sem identificador oficial numérico no cabeçalho", headerText));
                continue;
            }
            String officialFaqId = idMatcher.group(1);
            String question = idMatcher.group(2).strip();

            Element body = card.selectFirst(".card-body");
            String answer = body == null ? "" : blockText(body);
            if (answer.isBlank()) {
                failures.add(new ParseFailure(
                        "FAQ %s sem resposta".formatted(officialFaqId), headerText));
                continue;
            }

            Set<String> links = new LinkedHashSet<>();
            if (body != null) {
                for (Element a : body.select("a[href]")) {
                    String abs = a.absUrl("href");
                    if (!abs.isBlank()) links.add(abs);
                }
            }

            faqs.add(new ParsedFaq(officialFaqId, question, answer, new ArrayList<>(links), headerText));
        }
        return new CategoryParseResult(pageTitle, faqs, failures);
    }

    // ── Block-aware text extraction ──────────────────────────────────────────

    /**
     * Extracts text preserving paragraph boundaries, list items ("- " prefix),
     * line breaks, and every character of content (percentages, monetary values,
     * article numbers, Portuguese accents). Inline formatting is dropped.
     */
    public String blockText(Element root) {
        StringBuilder sb = new StringBuilder();
        appendNode(root, sb, false);
        // Collapse whitespace artifacts introduced by nesting.
        String text = sb.toString()
                .replace(' ', ' ')
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n");
        return text.strip();
    }

    private void appendNode(Node node, StringBuilder sb, boolean insideListItem) {
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode textNode) {
                String text = textNode.text();
                if (!text.isBlank() || (sb.length() > 0 && !endsWithNewline(sb))) {
                    sb.append(text);
                }
                continue;
            }
            if (!(child instanceof Element el)) continue;
            String tag = el.tagName().toLowerCase(Locale.ROOT);
            switch (tag) {
                case "br" -> sb.append('\n');
                case "li" -> {
                    ensureNewline(sb);
                    sb.append("- ");
                    appendNode(el, sb, true);
                    ensureNewline(sb);
                }
                case "p", "div", "table", "tr", "ul", "ol" -> {
                    if (!insideListItem) ensureParagraph(sb);
                    else ensureNewline(sb);
                    appendNode(el, sb, insideListItem);
                    if (!insideListItem) ensureParagraph(sb);
                    else ensureNewline(sb);
                }
                case "script", "style" -> { /* never include */ }
                default -> appendNode(el, sb, insideListItem);
            }
        }
    }

    private static boolean endsWithNewline(StringBuilder sb) {
        return sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n';
    }

    private static void ensureNewline(StringBuilder sb) {
        if (sb.length() > 0 && !endsWithNewline(sb)) sb.append('\n');
    }

    private static void ensureParagraph(StringBuilder sb) {
        if (sb.length() == 0) return;
        ensureNewline(sb);
        if (sb.length() >= 2 && sb.charAt(sb.length() - 2) != '\n') sb.append('\n');
    }

    /** Label normalization for comparisons: trim + collapse internal spaces. */
    public static String normalizeLabel(String label) {
        if (label == null) return "";
        return label.replace(' ', ' ').strip().replaceAll("\\s+", " ");
    }

    private static String snippet(Element el) {
        String text = el.text();
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}
