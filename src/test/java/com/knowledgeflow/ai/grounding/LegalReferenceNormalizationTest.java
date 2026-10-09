package com.knowledgeflow.ai.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * M4-GROUNDING-LEGAL-REF-NORMALIZATION: the same legal article written in different forms
 * ("artigo 52.º", "art. 52.º", "art 52", "(art. 52.º, n.º 1, …)") is the same reference;
 * a different article (53, 152, 5, 52-A) never is. Structural punctuation is a boundary.
 */
class LegalReferenceNormalizationTest {

    private static final GroundingProperties PROPS_REJECT =
            new GroundingProperties(true, 1, 1, 0.0, true, true);

    private final AnswerGroundingValidator validator = new AnswerGroundingValidator(PROPS_REJECT);

    private static List<RetrievedCase> ctx(String content) {
        return List.of(new RetrievedCase("Fonte", "Pergunta?", content, 0.9));
    }

    private GroundingValidationResult validate(String context, String answer) {
        return validator.validate(answer, ctx(context));
    }

    private static SensitiveClaim legalClaim(GroundingValidationResult result) {
        return result.allClaims().stream()
                .filter(c -> c.type() == SensitiveClaimType.LEGAL_REFERENCE)
                .findFirst().orElseThrow();
    }

    // ------------------------------------------------------------ positivos A–E

    @ParameterizedTest(name = "{0}: contexto \"{1}\" suporta \"{2}\"")
    @CsvSource(delimiter = '|', value = {
            "A | Prazo de 10 anos civis subsequentes (art. 52.º, n.º 1, do CIVA). | Nos termos do artigo 52.º do CIVA.",
            "B | Nos termos do artigo 52.º do CIVA.                              | Conforme o art. 52.º do CIVA.",
            "C | Conservação dos registos (art. 52.º, n.º 1).                     | Segundo o artigo 52.º, os registos devem ser conservados.",
            "D | Deduções aos rendimentos prediais: CIRS, art. 41.º               | O artigo 41.º do CIRS enumera os gastos.",
            "E | Opção pela tributação conjunta: CIMI, art. 135.º-D               | Prevista no artigo 135.º-D do CIMI.",
            "E2| Opção pela tributação conjunta: CIMI, art. 135.º-D               | Prevista no art 135-D do CIMI.",
            "F2| Ver artigo 52 do CIVA                                            | Conforme o art. 52.º, n.º 1, do CIVA."
    })
    void sameArticleInAnotherFormIsSupported(String id, String context, String answer) {
        var result = validate(context.strip(), answer.strip());
        assertThat(legalClaim(result).supported()).as(id).isTrue();
        assertThat(result.rejected()).as(id).isFalse();
    }

    // ------------------------------------------------------------ negativos F–J

    @ParameterizedTest(name = "{0}: contexto \"{1}\" NÃO suporta \"{2}\"")
    @CsvSource(delimiter = '|', value = {
            "F | Prazo de conservação (art. 52.º, n.º 1, do CIVA). | Nos termos do artigo 53.º do CIVA.",
            "G | Regime previsto no art. 152.º do CIVA.             | Nos termos do artigo 52.º do CIVA.",
            "H | Prazo de conservação (art. 52.º, n.º 1, do CIVA). | Nos termos do artigo 5.º do CIVA.",
            "I | Prazo de conservação (art. 52.º, n.º 1, do CIVA). | Nos termos do artigo 52.º-A do CIVA.",
            "I2| Opção pela tributação conjunta: CIMI, art. 135.º-D | Nos termos do artigo 135.º do CIMI.",
            "J | Os registos devem ser conservados em boa ordem.    | Nos termos do artigo 78.º do CIVA.",
            "J2| Prazo de conservação (art. 52.º, n.º 1, do CIVA). | Nos termos do art. 25.º do CIVA."
    })
    void differentOrMissingArticleIsRejected(String id, String context, String answer) {
        var result = validate(context.strip(), answer.strip());
        assertThat(legalClaim(result).supported()).as(id).isFalse();
        assertThat(result.rejected()).as(id).isTrue();
    }

    // ------------------------------------------------------------ regressão DEMO 1

    @Test
    @DisplayName("DEMO 1 (falso positivo real): '10 anos' e 'artigo 52.º' suportados pelo contexto CIVA v2")
    void demo1RealContextIsAccepted() {
        String context = "Um sujeito passivo de IVA deve arquivar e conservar em boa ordem os registos e "
                + "respectivos documentos de suporte durante os 10 anos civis subsequentes (art. 52.º, n.º 1, do CIVA).";
        String answer = "Um sujeito passivo de IVA deve conservar os registos e respectivos documentos de suporte "
                + "durante 10 anos civis subsequentes, nos termos do artigo 52.º, n.º 1, do Código do IVA.";

        var result = validate(context, answer);

        assertThat(result.rejected()).isFalse();
        assertThat(result.unsupportedClaims()).isEmpty();
        assertThat(result.allClaims()).extracting(SensitiveClaim::type)
                .contains(SensitiveClaimType.DEADLINE, SensitiveClaimType.LEGAL_REFERENCE);
        assertThat(result.allClaims()).allMatch(SensitiveClaim::supported);
        assertThat(legalClaim(result).supportingSourceTitles()).containsExactly("Fonte");
    }

    @Test
    @DisplayName("DEMO 1 com artigo errado continua a ser rejeitada")
    void demo1WithWrongArticleIsRejected() {
        String context = "… durante os 10 anos civis subsequentes (art. 52.º, n.º 1, do CIVA).";
        var result = validate(context, "Deve conservar durante 10 anos civis, nos termos do artigo 53.º do CIVA.");
        assertThat(result.rejected()).isTrue();
        assertThat(result.rejectionReason()).contains("artigo 53.º");
    }

    // ------------------------------------------------------------ detecção e fronteiras

    @Test
    @DisplayName("'art.' passa a ser detectado: um artigo abreviado inexistente no contexto é verificado e rejeitado")
    void abbreviatedArticleIsDetected() {
        var result = validate("Os registos devem ser conservados.", "Conforme o art. 52.º do CIVA.");
        assertThat(result.allClaims()).extracting(SensitiveClaim::type).contains(SensitiveClaimType.LEGAL_REFERENCE);
        assertThat(result.rejected()).isTrue();
    }

    @Test
    @DisplayName("palavras que terminam em 'art' (ex.: 'parte 5') não são referências legais")
    void wordEndingInArtIsNotAReference() {
        var result = validate("Texto sem artigos.", "Na parte 5 do formulário indique o valor.");
        assertThat(result.allClaims()).noneMatch(c -> c.type() == SensitiveClaimType.LEGAL_REFERENCE);
    }

    @Test
    @DisplayName("hífen com espaços não é sufixo: 'artigo 52.º - A sociedade' é o artigo 52")
    void spacedHyphenIsNotASuffix() {
        var result = validate("Ver art. 52.º do CIVA.", "Nos termos do artigo 52.º - A sociedade deve conservar.");
        assertThat(legalClaim(result).supported()).isTrue();
    }

    @Test
    @DisplayName("sufixo exacto: 135.º-D não suporta 135.º-A")
    void differentSuffixIsRejected() {
        var result = validate("CIMI, art. 135.º-D", "Nos termos do artigo 135.º-A do CIMI.");
        assertThat(result.rejected()).isTrue();
    }

    // ------------------------------------------------------------ pontuação nas restantes claims

    @Test
    @DisplayName("pontuação estrutural é separador: '(10 anos civis)' suporta '10 anos'")
    void parenthesisIsABoundaryForDeadlines() {
        var result = validate("Conservação obrigatória (10 anos civis).", "O prazo é de 10 anos.");
        assertThat(result.rejected()).isFalse();
        assertThat(result.allClaims()).allMatch(SensitiveClaim::supported);
    }

    @Test
    @DisplayName("vírgula decimal não é separador: '6,5%' no contexto não suporta '5%'")
    void decimalCommaIsNotABoundary() {
        var result = validate("A taxa aplicável é de 6,5%.", "A taxa aplicável é de 5%.");
        assertThat(result.rejected()).isTrue();
    }

    @Test
    @DisplayName("fronteiras numéricas preservadas: '(152 dias)' não suporta '52 dias'")
    void numericBoundaryPreserved() {
        var result = validate("Prazo especial (152 dias).", "O prazo é de 52 dias.");
        assertThat(result.rejected()).isTrue();
    }

    // ------------------------------------------------------------ revisão independente (M1, M2, m1–m4, n1)

    @ParameterizedTest(name = "{0}: contexto \"{1}\" suporta \"{2}\"")
    @CsvSource(delimiter = '|', value = {
            "M1a | CIMI, art. 135.º–D                         | Prevista no artigo 135.º-D do CIMI.",
            "M1b | CIMI, art. 135.º-D                         | Prevista no artigo 135.º‑D do CIMI.",
            "M2a | Dedução: artigos 19.º e 20.º do CIVA.        | Nos termos do artigo 20.º do CIVA.",
            "M2b | Ver CIVA, arts. 19.º, 20.º e 21.º.           | Conforme o art. 21.º do CIVA.",
            "M2c | Nos termos do artigo 20.º do CIVA.           | Previsto nos artigos 19.º e 20.º do CIVA.",
            "m2a | Conservação: art.º 52 do CIVA.               | Nos termos do artigo 52.º do CIVA.",
            "m2b | Conservação: art.\u00A052.º do CIVA.          | Nos termos do artigo 52.º do CIVA.",
            "n1  | Conservação: art. 52.º do CIVA.              | Nos termos do artigo 52o do CIVA."
    })
    void reviewEquivalences(String id, String context, String answer) {
        var result = validate(context.strip(), answer.strip());
        if (id.equals("M2c")) {
            // "artigos 19.º e 20.º": 20 is in the context, 19 is not → rejected, and 19 is named.
            assertThat(result.rejected()).as(id).isTrue();
            assertThat(result.rejectionReason()).as(id).contains("19");
            return;
        }
        assertThat(result.allClaims()).as(id)
                .filteredOn(c -> c.type() == SensitiveClaimType.LEGAL_REFERENCE)
                .allMatch(SensitiveClaim::supported);
        assertThat(result.rejected()).as(id).isFalse();
    }

    @ParameterizedTest(name = "{0}: contexto \"{1}\" NÃO suporta \"{2}\"")
    @CsvSource(delimiter = '|', value = {
            "M1c | CIMI, art. 135.º–D                         | Nos termos do artigo 135.º do CIMI.",
            "M1d | CIMI, art. 135.º                           | Nos termos do artigo 135.º–D do CIMI.",
            "M2d | Dedução: artigos 19.º e 20.º do CIVA.        | Nos termos do artigo 22.º do CIVA.",
            "M2e | Nos termos do artigo 19.º do CIVA.           | Previsto nos artigos 19.º e 22.º do CIVA.",
            "m1  | Conservação (art. 52.º do CIVA).             | Nos termos do artigo 52.º-ABCD do CIVA.",
            "G2  | Conservação (art. 52.º do CIVA).             | Nos termos do artigo 152.º do CIVA.",
            "mix | Conservação (art. 52.º do CIVA).             | Ver artigo 52.º e também o artigo 53.º do CIVA."
    })
    void reviewNegatives(String id, String context, String answer) {
        var result = validate(context.strip(), answer.strip());
        assertThat(result.rejected()).as(id).isTrue();
    }

    @Test
    @DisplayName("m3: ':' e ';' entre dígitos não são separador — '1:5%' não suporta '5%'")
    void colonBetweenDigitsIsNotABoundary() {
        assertThat(validate("Rácio 1:5% aplicado.", "A taxa é de 5%.").rejected()).isTrue();
        assertThat(validate("Valores 1;5% aplicados.", "A taxa é de 5%.").rejected()).isTrue();
    }

    @Test
    @DisplayName("n2: '1art 5' não é referência legal")
    void artPrecededByDigitIsNotAReference() {
        var result = validate("Texto.", "Código 1art 5 do formulário.");
        assertThat(result.allClaims()).noneMatch(c -> c.type() == SensitiveClaimType.LEGAL_REFERENCE);
    }

    @Test
    @DisplayName("fontes de suporte: só os casos que contêm o artigo")
    void supportingSourcesAreOnlyCasesContainingTheArticle() {
        var context = List.of(
                new RetrievedCase("CIVA 52", "Conservação?", "Prazo (art. 52.º, n.º 1, do CIVA).", 0.9),
                new RetrievedCase("CIRS 41", "Deduções?", "Gastos dedutíveis (CIRS, art. 41.º).", 0.9));
        var result = validator.validate("Nos termos do artigo 52.º do CIVA.", context);
        assertThat(legalClaim(result).supportingSourceTitles()).containsExactly("CIVA 52");
    }

    @ParameterizedTest(name = "R1 {0}: contexto \"{1}\" NÃO suporta \"{2}\"")
    @CsvSource(delimiter = '|', value = {
            "a | Nos artigos 19.º e 20.º, 30 dias após a factura.  | Nos termos do artigo 30.º do CIVA.",
            "b | Nos artigos 36.º e 40.º, 650 000 € de limite.     | Nos termos do artigo 650.º do CIVA.",
            "c | Ver artigos 2.º e 3.º e 2 meses de prazo.         | Nos termos do artigo 4.º do CIVA.",
            "d | Ver artigos 19 e 20, 30 dias depois.              | Nos termos do artigo 30.º do CIVA.",
            "e | Ver artigos 19 e 20, 30 de junho de 2024.        | Nos termos do artigo 30.º do CIVA.",
            "O | Conservação (art. 52.º do CIVA).                 | Nos termos do artigo 52O do CIVA."
    })
    void listsNeverTurnQuantitiesIntoArticles(String id, String context, String answer) {
        assertThat(validate(context.strip(), answer.strip()).rejected()).as(id).isTrue();
    }

    @Test
    @DisplayName("R1: listas legítimas continuam expandidas (incl. sufixo)")
    void legitimateListsStillExpand() {
        assertThat(LegalArticleReference.extractAll("artigos 98.º e 99.º-D do CIRS, 30 dias"))
                .containsExactly("ART:98", "ART:99-D");
        assertThat(LegalArticleReference.extractAll("arts. 19.º, 20.º e 21.º do CIVA"))
                .containsExactly("ART:19", "ART:20", "ART:21");
    }
}
