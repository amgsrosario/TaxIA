package com.knowledgeflow.ingestion.atfaq;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ingestion.atfaq.AtFaqHtmlParser.CategoryParseResult;
import com.knowledgeflow.ingestion.atfaq.AtFaqHtmlParser.IndexLink;
import com.knowledgeflow.ingestion.atfaq.AtFaqHtmlParser.ParsedFaq;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AtFaqHtmlParserTest {

    private static final String BASE = "https://info.portaldasfinancas.gov.pt";
    private static final String PAGE_URL =
            BASE + "/pt/apoio_contribuinte/questoes_frequentes/pages/faqs-90002.aspx";

    private final AtFaqHtmlParser parser = new AtFaqHtmlParser();

    // ── Category page ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Extrai id oficial, pergunta e resposta de cada card")
    void parsesCategoryPage() {
        CategoryParseResult result = parser.parseCategoryPage(
                AtFaqFixtures.load("categoria-taxas.html"), PAGE_URL);

        assertThat(result.failures()).isEmpty();
        assertThat(result.faqs()).hasSize(4);
        assertThat(result.faqs()).extracting(ParsedFaq::officialFaqId)
                .containsExactly("4001", "4002", "4003", "4004");

        ParsedFaq first = result.faqs().get(0);
        assertThat(first.question())
                .startsWith("Taxas - Qual é a taxa de IVA aplicável");
        assertThat(first.answer())
                .contains("taxa reduzida de 6%")
                .contains("verba 2.36 da Lista I")
                .contains("artigo 18.º do CIVA");
    }

    @Test
    @DisplayName("Preserva percentagens, valores monetários, prazos e acentuação portuguesa")
    void preservesSemanticContent() {
        CategoryParseResult result = parser.parseCategoryPage(
                AtFaqFixtures.load("categoria-taxas.html"), PAGE_URL);

        ParsedFaq prazo = result.faqs().get(2);
        assertThat(prazo.answer())
                .contains("€ 650.000,00")
                .contains("dia 20 do 2.º mês seguinte")
                .contains("artigo 41.º")
                .contains("15 dias");

        ParsedFaq acentos = result.faqs().get(3);
        assertThat(acentos.answer())
                .contains("transmissão", "aquisição", "isenção", "dedução",
                        "çedilha", "coração", "âmbito", "pêssego", "índice", "União Europeia")
                .contains("€")
                .contains("23%");
    }

    @Test
    @DisplayName("Preserva listas como linhas com prefixo '- '")
    void preservesLists() {
        CategoryParseResult result = parser.parseCategoryPage(
                AtFaqFixtures.load("categoria-taxas.html"), PAGE_URL);

        ParsedFaq lista = result.faqs().get(1);
        assertThat(lista.answer())
                .contains("- Produtos alimentares essenciais;")
                .contains("- Publicações periódicas e livros;")
                .contains("- Prestações de serviços de reparação previstas na verba 2.36.");
    }

    @Test
    @DisplayName("Extrai links absolutos das respostas, incluindo externos (para sinalização)")
    void extractsLinks() {
        CategoryParseResult result = parser.parseCategoryPage(
                AtFaqFixtures.load("categoria-faturacao.html"), PAGE_URL);

        ParsedFaq comLinks = result.faqs().get(0);
        assertThat(comLinks.links())
                .contains("https://evil.example.com/phishing")
                .anyMatch(l -> l.startsWith(BASE + "/pt/apoio_contribuinte/"));
    }

    @Test
    @DisplayName("FAQ sem identificador oficial é sinalizada como falha, não importada")
    void faqWithoutIdIsFailure() {
        CategoryParseResult result = parser.parseCategoryPage(
                AtFaqFixtures.load("categoria-faturacao.html"), PAGE_URL);

        assertThat(result.faqs()).extracting(ParsedFaq::officialFaqId)
                .containsExactly("4201");
        assertThat(result.failures())
                .anyMatch(f -> f.reason().contains("sem identificador oficial"));
    }

    @Test
    @DisplayName("FAQ sem resposta é sinalizada como falha")
    void faqWithoutAnswerIsFailure() {
        CategoryParseResult result = parser.parseCategoryPage(
                AtFaqFixtures.load("categoria-faturacao.html"), PAGE_URL);

        assertThat(result.failures())
                .anyMatch(f -> f.reason().contains("4203 sem resposta"));
    }

    @Test
    @DisplayName("HTML truncado não rebenta — devolve o que conseguir sem inventar respostas")
    void truncatedHtmlIsHandledGracefully() {
        CategoryParseResult result = parser.parseCategoryPage(
                AtFaqFixtures.load("html-incompleto.html"), PAGE_URL);

        // The single truncated card has no answer body → failure, zero parsed FAQs.
        assertThat(result.faqs()).isEmpty();
        assertThat(result.failures()).isNotEmpty();
    }

    @Test
    @DisplayName("Página sem o acordeão esperado devolve falha estrutural")
    void pageWithoutAccordionIsStructuralFailure() {
        CategoryParseResult result = parser.parseCategoryPage(
                AtFaqFixtures.load("pagina-sem-accordion.html"), PAGE_URL);

        assertThat(result.faqs()).isEmpty();
        assertThat(result.failures())
                .anyMatch(f -> f.reason().contains("faqAccordion"));
    }

    // ── Index page ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Índice: extrai apenas os links da área IVA, com grupo e rótulo")
    void parsesIndexIvaAreaOnly() {
        List<IndexLink> links = parser.parseIndex(
                AtFaqFixtures.loadIndex(BASE), BASE + "/pt/x/Pages/faqs.aspx", "IVA");

        // IRC's "Taxas" (faqs-80001) must never appear.
        assertThat(links).noneMatch(l -> l.url().contains("faqs-80001"));
        assertThat(links).anyMatch(l ->
                l.linkLabel().equals("Direito à Dedução") && l.url().contains("faqs-90001"));
        assertThat(links).anyMatch(l ->
                l.groupLabel().equals("Faturação") && l.url().contains("faqs-90004"));
    }

    @Test
    @DisplayName("Índice: área inexistente devolve lista vazia")
    void unknownAreaYieldsNothing() {
        List<IndexLink> links = parser.parseIndex(
                AtFaqFixtures.loadIndex(BASE), BASE + "/pt/x/Pages/faqs.aspx", "IMPOSTO INEXISTENTE");
        assertThat(links).isEmpty();
    }
}
