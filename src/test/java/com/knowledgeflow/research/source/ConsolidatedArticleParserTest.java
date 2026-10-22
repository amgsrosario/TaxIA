package com.knowledgeflow.research.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.research.ResearchFixtures;
import com.knowledgeflow.research.enums.ValidityStatus;
import com.knowledgeflow.research.source.ConsolidatedArticleParser.ParsedArticle;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fixtures: páginas oficiais reais do Portal das Finanças obtidas por consulta read-only em
 * 2026-10-22 (CIRS art. 78.º, CIVA art. 9.º); texto oficial inalterado ({@link ResearchFixtures}).
 */
class ConsolidatedArticleParserTest {

    private static final LocalDate RETRIEVAL = LocalDate.of(2026, 10, 22);
    private static final LegislationReference CIRS_78 = new LegislationReference("CIRS", 78, null);
    private static final LegislationReference CIVA_9 = new LegislationReference("CIVA", 9, null);

    private final ConsolidatedArticleParser parser = new ConsolidatedArticleParser();

    private static String fixture(String name) {
        return ResearchFixtures.page(name);
    }

    @Test
    @DisplayName("CIRS 78.º real: confirma o artigo, extrai texto oficial, notas e números revogados")
    void parsesRealCirsArticle() {
        ParsedArticle a = parser.parse(fixture("cirs-art78-2026-10-22.html"), CIRS_78, RETRIEVAL);

        assertThat(a.confirmed()).isTrue();
        assertThat(a.heading()).isEqualTo("Artigo 78.º");
        assertThat(a.epigraph()).isEqualTo("Deduções à coleta");
        assertThat(a.content()).contains("1 - À coleta são efetuadas, nos termos dos artigos subsequentes");
        assertThat(a.content()).contains("5 - As deduções previstas no n.º 1 aplicam-se apenas aos sujeitos passivos residentes em território português.");
        assertThat(a.revokedItems()).contains("4");
        assertThat(a.amendmentNotes()).anyMatch(n -> n.contains("Redação da Lei n.º 42/2016"));
        assertThat(a.amendmentNotes()).anyMatch(n -> n.startsWith("Alterado por:") && n.contains("49/2025"));
        assertThat(a.validityStatus()).isEqualTo(ValidityStatus.CONSOLIDATED_AT_RETRIEVAL);
        assertThat(a.validityReason()).contains("2026-10-22").contains("sem verificação humana");
    }

    @Test
    @DisplayName("Exclui navegação, scripts, notas de redacção e ligações a redacções anteriores do conteúdo")
    void excludesNonOfficialContent() {
        ParsedArticle a = parser.parse(fixture("cirs-art78-2026-10-22.html"), CIRS_78, RETRIEVAL);

        assertThat(a.content())
                .doesNotContain("Links Úteis")
                .doesNotContain("document).ready")
                .doesNotContain("Artigo alterado por")
                .doesNotContain("março de 2016")
                .doesNotContain("(Redação da Lei")
                .doesNotContain("​");
    }

    @Test
    @DisplayName("CIVA 9.º real: confirma o artigo mesmo com títulos de capítulo antes do cabeçalho")
    void parsesRealCivaArticle() {
        ParsedArticle a = parser.parse(fixture("civa-art9-2026-10-22.html"), CIVA_9, RETRIEVAL);

        assertThat(a.confirmed()).isTrue();
        assertThat(a.epigraph()).isEqualTo("Isenções nas operações internas");
        assertThat(a.content()).contains("Estão isentas do imposto:")
                .contains("4) As transmissões de órgãos, sangue e leite humanos;")
                .doesNotContain("CAPÍTULO II");
    }

    @Test
    @DisplayName("A página de outro artigo não confirma o artigo pedido (78 ≠ 78.º-A ≠ 79)")
    void wrongArticleIsNotConfirmed() {
        String html = fixture("cirs-art78-2026-10-22.html");
        assertThat(parser.parse(html, new LegislationReference("CIRS", 79, null), RETRIEVAL).confirmed()).isFalse();
        ParsedArticle suffix = parser.parse(html, new LegislationReference("CIRS", 78, "A"), RETRIEVAL);
        assertThat(suffix.confirmed()).isFalse();
        assertThat(suffix.detail()).contains("Artigo 78.º").contains("78.º-A");
    }

    @Test
    @DisplayName("Nota de alteração com ano posterior à consulta → vigência UNCERTAIN")
    void laterAmendmentYearMakesValidityUncertain() {
        ParsedArticle a = parser.parse(fixture("cirs-art78-2026-10-22.html"), CIRS_78, LocalDate.of(2024, 1, 10));

        assertThat(a.confirmed()).isTrue();
        assertThat(a.validityStatus()).isEqualTo(ValidityStatus.UNCERTAIN);
        assertThat(a.validityReason()).contains("2025");
    }

    @Test
    @DisplayName("Artigo integralmente revogado → REVOKED (página sintética com a estrutura oficial)")
    void wholeArticleRevoked() {
        String html = """
                <html><body><div id="BODYCONTENT"><span class="titulos">Artigo 12.º<br>Epígrafe antiga</span>
                <p>(Revogado.)</p></div></body></html>""";
        ParsedArticle a = parser.parse(html, new LegislationReference("CIRS", 12, null), RETRIEVAL);

        assertThat(a.confirmed()).isTrue();
        assertThat(a.validityStatus()).isEqualTo(ValidityStatus.REVOKED);
    }

    @Test
    @DisplayName("Sem bloco oficial ou página vazia → não confirmado")
    void missingBodyIsNotConfirmed() {
        assertThat(parser.parse("<html><body><p>Artigo 78.º</p></body></html>", CIRS_78, RETRIEVAL).confirmed()).isFalse();
        assertThat(parser.parse("", CIRS_78, RETRIEVAL).confirmed()).isFalse();
    }
}
