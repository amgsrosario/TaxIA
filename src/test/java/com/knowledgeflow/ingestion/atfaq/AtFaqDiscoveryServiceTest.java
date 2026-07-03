package com.knowledgeflow.ingestion.atfaq;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ingestion.atfaq.AtFaqDiscoveryService.DiscoveredCategory;
import com.knowledgeflow.ingestion.atfaq.AtFaqDiscoveryService.DiscoveryOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AtFaqDiscoveryServiceTest {

    private static final String BASE = "https://info.portaldasfinancas.gov.pt";
    private static final String INDEX_URL =
            BASE + "/pt/apoio_contribuinte/questoes_frequentes/Pages/faqs.aspx";

    private AtFaqProperties properties;
    private AtFaqDiscoveryService discovery;

    @BeforeEach
    void setUp() {
        properties = new AtFaqProperties();
        discovery = new AtFaqDiscoveryService(properties, new AtFaqHtmlParser());
    }

    private DiscoveryOutcome discover() {
        return discovery.discoverFromIndexHtml(AtFaqFixtures.loadIndex(BASE), INDEX_URL);
    }

    @Test
    @DisplayName("Só as subcategorias autorizadas do IVA são descobertas")
    void discoversOnlyAuthorizedCategories() {
        DiscoveryOutcome outcome = discover();

        assertThat(outcome.categories())
                .extracting(DiscoveredCategory::subcategory)
                .containsExactlyInAnyOrder("Direito à Dedução", "Taxas", "Faturação");
        // Out-of-scope pages: Isenções (90003), Débito Direto (90005), IRC (80001).
        assertThat(outcome.categories())
                .extracting(DiscoveredCategory::url)
                .noneMatch(u -> u.contains("faqs-90003")
                        || u.contains("faqs-90005")
                        || u.contains("faqs-80001"));
    }

    @Test
    @DisplayName("URLs relativas são resolvidas para absolutas na base autorizada")
    void resolvesRelativeUrls() {
        DiscoveryOutcome outcome = discover();
        assertThat(outcome.categories())
                .extracting(DiscoveredCategory::url)
                .allMatch(u -> u.startsWith(BASE + "/pt/apoio_contribuinte/questoes_frequentes/"));
    }

    @Test
    @DisplayName("URLs duplicadas são contadas e recolhidas uma única vez")
    void deduplicatesUrls() {
        DiscoveryOutcome outcome = discover();
        assertThat(outcome.duplicatesSkipped()).isEqualTo(1);
        assertThat(outcome.categories())
                .extracting(DiscoveredCategory::url)
                .filteredOn(u -> u.contains("faqs-90002"))
                .hasSize(1);
    }

    @Test
    @DisplayName("Hosts externos e caminhos fora do prefixo são rejeitados")
    void rejectsExternalAndOffPrefixUrls() {
        DiscoveryOutcome outcome = discover();
        assertThat(outcome.rejectedUrls())
                .anyMatch(u -> u.contains("evil.example.com"))
                .anyMatch(u -> u.contains("/outra/seccao/"));
        assertThat(outcome.categories())
                .extracting(DiscoveredCategory::url)
                .noneMatch(u -> u.contains("evil.example.com"));
    }

    @Test
    @DisplayName("Limite: sem categorias autorizadas configuradas, nada é descoberto")
    void emptyAllowlistDiscoversNothing() {
        properties.setAllowedSubcategories(java.util.List.of());
        DiscoveryOutcome outcome = discover();
        assertThat(outcome.categories()).isEmpty();
    }
}
