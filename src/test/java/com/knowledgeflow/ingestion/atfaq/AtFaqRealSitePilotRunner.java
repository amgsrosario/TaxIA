package com.knowledgeflow.ingestion.atfaq;

import com.knowledgeflow.ingestion.atfaq.AtFaqDiscoveryService.DiscoveredCategory;
import com.knowledgeflow.ingestion.atfaq.AtFaqDiscoveryService.DiscoveryOutcome;
import com.knowledgeflow.ingestion.atfaq.AtFaqHtmlParser.CategoryParseResult;
import com.knowledgeflow.ingestion.atfaq.AtFaqHtmlParser.ParsedFaq;
import com.knowledgeflow.ingestion.atfaq.AtFaqHttpClient.FetchResult;
import com.knowledgeflow.ingestion.atfaq.AtFaqHttpClient.Stats;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Ensaio real limitado da Etapa 10A (§23) — execução MANUAL, nunca corre nas suites.
 * <p>
 * Dry-run puro contra o site real da AT: máximo 5 páginas, máximo 10 FAQs,
 * pausa entre pedidos, paragem imediata em 403/429/503, sem qualquer
 * persistência, importação ou publicação. Nenhuma base de dados é tocada.
 * <p>
 * Executar:
 * <pre>
 * mvn test-compile
 * mvn org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.knowledgeflow.ingestion.atfaq.AtFaqRealSitePilotRunner \
 *     -Dexec.classpathScope=test
 * </pre>
 */
public final class AtFaqRealSitePilotRunner {

    private static final int MAX_PAGES = 5;
    private static final int MAX_FAQS = 10;

    private AtFaqRealSitePilotRunner() {
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

        AtFaqProperties props = new AtFaqProperties();
        // Real-site defaults apply (host allowlist, 1500 ms delay, timeouts).
        AtFaqHttpClient http = new AtFaqHttpClient(props);
        AtFaqHtmlParser parser = new AtFaqHtmlParser();
        AtFaqNormalizer normalizer = new AtFaqNormalizer();
        AtFaqLegalReferenceExtractor legalRefs = new AtFaqLegalReferenceExtractor();
        AtFaqDiscoveryService discovery = new AtFaqDiscoveryService(props, parser);

        Stats stats = new Stats();
        long start = System.nanoTime();
        int pagesFetched = 0;
        int faqsParsed = 0;
        int parseFailures = 0;
        int legalRefCount = 0;

        out.println("== Ensaio real limitado AT FAQ (dry-run, sem persistência) ==");
        out.println("Limites: " + MAX_PAGES + " páginas, " + MAX_FAQS + " FAQs, delay "
                + props.getDelayMs() + " ms");

        try {
            FetchResult index = http.fetch(props.indexUrl(), null, null, stats);
            pagesFetched++;
            out.println("[1] Índice: HTTP " + index.statusCode()
                    + " (" + index.body().length() + " chars)");

            DiscoveryOutcome outcome = discovery.discoverFromIndexHtml(index.body(), props.indexUrl());
            out.println("[2] Descoberta: " + outcome.categories().size()
                    + " categorias autorizadas, " + outcome.rejectedUrls().size()
                    + " rejeitadas, " + outcome.duplicatesSkipped() + " duplicadas");
            for (DiscoveredCategory c : outcome.categories()) {
                out.println("      - " + c.subcategory() + " -> " + c.url());
            }

            for (DiscoveredCategory category : outcome.categories()) {
                if (pagesFetched >= MAX_PAGES || faqsParsed >= MAX_FAQS) break;

                FetchResult page = http.fetch(category.url(), null, null, stats);
                pagesFetched++;
                CategoryParseResult parsed = parser.parseCategoryPage(page.body(), category.url());
                parseFailures += parsed.failures().size();

                out.println("[3] " + category.subcategory() + ": HTTP " + page.statusCode()
                        + ", " + parsed.faqs().size() + " FAQs, "
                        + parsed.failures().size() + " falhas de parsing"
                        + " — título: " + parsed.pageTitle());

                for (ParsedFaq faq : parsed.faqs()) {
                    if (faqsParsed >= MAX_FAQS) break;
                    faqsParsed++;
                    String q = normalizer.normalize(faq.question());
                    String a = normalizer.normalize(faq.answer());
                    String hash = normalizer.contentHash(q, a);
                    var refs = legalRefs.extract(a);
                    legalRefCount += refs.size();
                    out.println("      FAQ " + faq.officialFaqId()
                            + " | hash " + hash.substring(0, 12) + "…"
                            + " | " + a.length() + " chars"
                            + " | refs legais: " + refs);
                    out.println("        Q: " + (q.length() > 110 ? q.substring(0, 110) + "…" : q));
                }
            }

            out.println();
            out.println("== RELATÓRIO DO ENSAIO ==");
            out.println("Pedidos HTTP:      " + stats.requests());
            out.println("Retries:           " + stats.retries());
            out.println("Códigos HTTP:      " + stats.statusCodes());
            out.println("Páginas obtidas:   " + pagesFetched);
            out.println("FAQs parseadas:    " + faqsParsed + " (limite " + MAX_FAQS + ")");
            out.println("Falhas parsing:    " + parseFailures);
            out.println("Refs legais:       " + legalRefCount);
            out.println("Duração:           " + ((System.nanoTime() - start) / 1_000_000) + " ms");
            out.println("Persistência:      NENHUMA (dry-run puro, sem BD)");

        } catch (AtFaqExceptions.SourceBlockedException e) {
            out.println("!! FONTE BLOQUEOU (HTTP " + e.getStatusCode() + ") — ensaio parado "
                    + "imediatamente, como mandam as regras: " + e.getMessage());
        } catch (AtFaqExceptions.SecurityBlockedException e) {
            out.println("!! BLOQUEIO DE SEGURANÇA — ensaio parado: " + e.getMessage());
        }
    }
}
