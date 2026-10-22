package com.knowledgeflow.research.source;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OfficialLegislationCatalogTest {

    private final OfficialLegislationCatalog catalog = new OfficialLegislationCatalog(new ResearchProperties());

    @Test
    @DisplayName("Constrói o URL oficial verificado, incluindo artigos com letra")
    void buildsOfficialUrls() {
        String base = "https://info.portaldasfinancas.gov.pt/pt/informacao_fiscal/codigos_tributarios";
        assertThat(catalog.urlFor(new LegislationReference("CIRS", 78, null)))
                .contains(base + "/cirs_rep/Pages/irs78.aspx");
        assertThat(catalog.urlFor(new LegislationReference("cirs", 78, "a")))
                .contains(base + "/cirs_rep/Pages/irs78a.aspx");
        assertThat(catalog.urlFor(new LegislationReference("CIVA", 9, null)))
                .contains(base + "/civa_rep/Pages/iva9.aspx");
        assertThat(catalog.urlFor(new LegislationReference("CIRC", 23, null)))
                .contains(base + "/circ/Pages/irc23.aspx");
        assertThat(catalog.urlFor(new LegislationReference("LGT", 1, null)))
                .contains(base + "/lgt/Pages/lgt1.aspx");
    }

    @Test
    @DisplayName("Código fora do catálogo não tem URL (não é consultado)")
    void unknownCodeHasNoUrl() {
        assertThat(catalog.urlFor(new LegislationReference("EBF", 1, null))).isEmpty();
        assertThat(catalog.supports("CIMI")).isFalse();
    }

    @Test
    @DisplayName("Extrai referências explícitas da pergunta, com várias formas e sem duplicados")
    void extractsReferencesFromQuestion() {
        assertThat(catalog.referencesIn(
                "Segundo o artigo 78.º do CIRS e o art. 9.º do CIVA, e o artigo 78.º-A do Código do IRS; "
                        + "artigo 78.º do CIRS outra vez."))
                .extracting(LegislationReference::key)
                .containsExactly("CIRS:78", "CIVA:9", "CIRS:78-A");
        assertThat(catalog.referencesIn("artigo 23.º da Lei Geral Tributária")).extracting(LegislationReference::key)
                .containsExactly("LGT:23");
        assertThat(catalog.referencesIn("Posso deduzir as despesas do ginásio no IRS?")).isEmpty();
        assertThat(catalog.referencesIn("artigo 1.º do EBF")).isEmpty();
    }
}
