package com.knowledgeflow.ai.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for monetary-value detection and normalization in the grounding validator.
 * §9 — normalization via MonetaryValueNormalizer
 * §13 — end-to-end grounding via AnswerGroundingValidator
 */
class MonetaryDetectionTest {

    private static final GroundingProperties PROPS_REJECT =
            new GroundingProperties(true, 1, 1, 0.0, true, true);

    private AnswerGroundingValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AnswerGroundingValidator(PROPS_REJECT);
    }

    // =========================================================================
    // §9.1 — MonetaryValueNormalizer unit tests
    // =========================================================================

    @Test
    void normalize_plainNumber() {
        assertAmount("650000", "650000");
    }

    @Test
    void normalize_thousandsSeparatorSpace() {
        assertAmount("650 000", "650000");
    }

    @Test
    void normalize_thousandsSeparatorDot() {
        assertAmount("650.000", "650000");
    }

    @Test
    void normalize_suffixEuroSpace() {
        assertAmount("650 000 €", "650000");
        assertCurrency("650 000 €", NormalizedMonetaryValue.EUR);
    }

    @Test
    void normalize_suffixEuroNoSpace() {
        assertAmount("650000€", "650000");
        assertCurrency("650000€", NormalizedMonetaryValue.EUR);
    }

    @Test
    void normalize_dotThousandsSuffixEuro() {
        assertAmount("650.000 €", "650000");
    }

    @Test
    void normalize_prefixEuro() {
        assertAmount("€650000", "650000");
        assertCurrency("€650000", NormalizedMonetaryValue.EUR);
    }

    @Test
    void normalize_prefixEuroWithSpace() {
        assertAmount("€ 650 000", "650000");
    }

    @Test
    void normalize_eurSuffix() {
        assertAmount("650000 EUR", "650000");
        assertCurrency("650000 EUR", NormalizedMonetaryValue.EUR);
    }

    @Test
    void normalize_eurosSuffix() {
        assertAmount("650 000 euros", "650000");
        assertCurrency("650 000 euros", NormalizedMonetaryValue.EUR);
    }

    @Test
    void normalize_withDecimalComma() {
        assertAmount("1.650.000,50 €", "1650000.50");
    }

    @Test
    void normalize_smallAmountNoSeparator() {
        assertAmount("5000 €", "5000");
    }

    @Test
    void normalize_eurPrefix() {
        assertAmount("EUR 650 000", "650000");
        assertCurrency("EUR 650 000", NormalizedMonetaryValue.EUR);
    }

    @Test
    void normalize_decimalDot() {
        // "650000.50 €" — dot followed by 2 digits → decimal
        assertAmount("650000.50 €", "650000.50");
    }

    @Test
    void normalize_sameAmountDifferentFormats_bigDecimalEqual() {
        // BigDecimal.compareTo ensures 650000 == 650000.00
        NormalizedMonetaryValue a = MonetaryValueNormalizer.normalize("650000 €").orElseThrow();
        NormalizedMonetaryValue b = MonetaryValueNormalizer.normalize("650 000 €").orElseThrow();
        NormalizedMonetaryValue c = MonetaryValueNormalizer.normalize("650.000€").orElseThrow();
        assertThat(a.sameAs(b)).isTrue();
        assertThat(b.sameAs(c)).isTrue();
    }

    // =========================================================================
    // §9.2 — Detection: supported when same value
    // =========================================================================

    @Test
    void monetary_sameValue_suffixEuro_supported() {
        var context = contextWith("O limiar é de 650 000 €.");
        var result = validator.validate("Quando o volume excede 650 000 €, aplica-se o regime mensal.", context);
        assertThat(result.rejected()).isFalse();
        assertMonetarySupported(result, true);
    }

    @Test
    void monetary_contextHasDot_answerHasSpace_sameValue_supported() {
        var context = contextWith("Volume de negócios superior a 650.000 € obriga a periodicidade mensal.");
        var result = validator.validate("O limiar de 650 000 € aplica a periodicidade mensal.", context);
        assertThat(result.rejected()).isFalse();
        assertMonetarySupported(result, true);
    }

    @Test
    void monetary_contextHasSpace_answerHasEurPrefix_sameValue_supported() {
        // "€ 650 000" in answer, "650 000 €" in context — same value
        var context = contextWith("O limiar é de 650 000 €.");
        var result = validator.validate("Ao atingir € 650 000 de volume, aplica-se o regime mensal.", context);
        assertThat(result.rejected()).isFalse();
    }

    @Test
    void monetary_contextHasEurSuffix_answerHasEurSuffix_sameValue_supported() {
        var context = contextWith("Limite de 10000 EUR para isenção.");
        var result = validator.validate("Entidades abaixo de 10000 EUR ficam isentas.", context);
        assertThat(result.rejected()).isFalse();
    }

    @Test
    void monetary_eurPrefixInAnswer_supported() {
        var context = contextWith("O limiar é de 650 000 €.");
        var result = validator.validate("O limiar é EUR 650 000.", context);
        assertThat(result.rejected()).isFalse();
    }

    // =========================================================================
    // §9.2 — Detection: unsupported when different value
    // =========================================================================

    @Test
    void monetary_differentValue_unsupported() {
        var context = contextWith("O limiar é de 650 000 €.");
        var result = validator.validate("O limiar para periodicidade mensal é de 200 000 €.", context);
        assertMonetaryUnsupported(result);
        assertThat(result.rejected()).isTrue();
    }

    @Test
    void monetary_differentValueDotFormat_unsupported() {
        var context = contextWith("O limiar é de 650.000 €.");
        var result = validator.validate("O limiar para periodicidade mensal é de 200.000 €.", context);
        assertMonetaryUnsupported(result);
    }

    @Test
    void monetary_answerHasEurPrefix_differentValue_unsupported() {
        var context = contextWith("Entidades acima de 650 000 € são tributadas à taxa normal.");
        var result = validator.validate("Entidades acima de € 200 000 são tributadas.", context);
        assertMonetaryUnsupported(result);
    }

    @Test
    void monetary_eurSuffix_differentValue_unsupported() {
        var context = contextWith("Limite de 10000 EUR para isenção.");
        var result = validator.validate("Entidades abaixo de 5000 EUR ficam isentas.", context);
        assertMonetaryUnsupported(result);
    }

    // =========================================================================
    // §9.3 — Edge cases: false positives must NOT trigger monetary claims
    // =========================================================================

    @Test
    void monetary_percentNotConfusedWithMonetary() {
        var context = contextWith("A taxa de IVA é de 23%.");
        var result = validator.validate("A taxa aplicável é de 23%.", context);
        assertThat(result.rejected()).isFalse();
        boolean hasRate = result.allClaims().stream()
                .anyMatch(c -> c.type() == SensitiveClaimType.TAX_RATE && c.supported());
        assertThat(hasRate).isTrue();
        boolean hasMoney = result.allClaims().stream()
                .anyMatch(c -> c.type() == SensitiveClaimType.MONETARY_THRESHOLD);
        assertThat(hasMoney).isFalse();
    }

    @Test
    void monetary_yearNotConfusedWithMonetary() {
        // "2025" alone (no currency) must NOT create a monetary claim
        var context = contextWith("A lei foi publicada em 2025.");
        var result = validator.validate("Este regime entrou em vigor em 2025.", context);
        boolean hasMoney = result.allClaims().stream()
                .anyMatch(c -> c.type() == SensitiveClaimType.MONETARY_THRESHOLD);
        assertThat(hasMoney).isFalse();
    }

    @Test
    void monetary_portNumberNotConfusedWithMonetary() {
        // "8082" alone (no currency) must NOT create a monetary claim
        var context = contextWith("O serviço corre na porta 8082.");
        var result = validator.validate("Aceda à porta 8082.", context);
        boolean hasMoney = result.allClaims().stream()
                .anyMatch(c -> c.type() == SensitiveClaimType.MONETARY_THRESHOLD);
        assertThat(hasMoney).isFalse();
    }

    @Test
    void monetary_nifNotConfusedWithMonetary() {
        // "123456789" NIF without currency → not a monetary claim
        var context = contextWith("NIF do contribuinte: 123456789.");
        var result = validator.validate("O NIF é 123456789.", context);
        boolean hasMoney = result.allClaims().stream()
                .anyMatch(c -> c.type() == SensitiveClaimType.MONETARY_THRESHOLD);
        assertThat(hasMoney).isFalse();
    }

    // =========================================================================
    // §9.4 — Multiple values, deduplication, large amounts
    // =========================================================================

    @Test
    void monetary_multipleValues_oneSupportedOneNot() {
        var context = contextWith("Contribuintes acima de 650 000 € entregam declaração mensal.");
        var result = validator.validate(
                "Contribuintes acima de 650 000 € devem pagar. O limite anterior era 200 000 €.", context);
        long supported = result.allClaims().stream()
                .filter(c -> c.type() == SensitiveClaimType.MONETARY_THRESHOLD && c.supported()).count();
        long unsupported = result.allClaims().stream()
                .filter(c -> c.type() == SensitiveClaimType.MONETARY_THRESHOLD && !c.supported()).count();
        assertThat(supported).isGreaterThanOrEqualTo(1);
        assertThat(unsupported).isGreaterThanOrEqualTo(1);
    }

    @Test
    void monetary_largeValueWith3Groups_supported() {
        var context = contextWith("Limite máximo de 1.650.000 € para este regime.");
        var result = validator.validate("O limite máximo é de 1.650.000 €.", context);
        assertThat(result.rejected()).isFalse();
    }

    @Test
    void monetary_contextHasNoMonetaryValue_unsupported() {
        var context = contextWith("Consulte a legislação para conhecer os limiares aplicáveis.");
        var result = validator.validate("O limiar é de 650 000 €.", context);
        assertMonetaryUnsupported(result);
        assertThat(result.rejected()).isTrue();
    }

    @Test
    void monetary_deduplication_sameValueTwice_oneClaim() {
        var context = contextWith("O limiar de 650 000 € aplica-se a todos.");
        var result = validator.validate(
                "O limiar é 650 000 €. Reitero: o limiar é 650.000 €.", context);
        long moneyCount = result.allClaims().stream()
                .filter(c -> c.type() == SensitiveClaimType.MONETARY_THRESHOLD).count();
        assertThat(moneyCount).isEqualTo(1);
    }

    @Test
    void monetary_withDecimalAmount_supported() {
        // "1.650.000,50 €" — full PT format with decimal
        var context = contextWith("Limite de 1.650.000,50 € para aplicação do regime.");
        var result = validator.validate("O limite de 1.650.000,50 € aplica-se.", context);
        assertThat(result.rejected()).isFalse();
    }

    // =========================================================================
    // helpers
    // =========================================================================

    private List<RetrievedCase> contextWith(String content) {
        return List.of(new RetrievedCase("Fonte", "Pergunta de teste", content, 0.9));
    }

    private void assertAmount(String raw, String expectedPlain) {
        NormalizedMonetaryValue nmv = MonetaryValueNormalizer.normalize(raw).orElseThrow(
                () -> new AssertionError("normalize() returned empty for: " + raw));
        assertThat(nmv.amount().toPlainString())
                .as("amount for raw=%s", raw)
                .isEqualTo(expectedPlain);
    }

    private void assertCurrency(String raw, String expectedCurrency) {
        NormalizedMonetaryValue nmv = MonetaryValueNormalizer.normalize(raw).orElseThrow(
                () -> new AssertionError("normalize() returned empty for: " + raw));
        assertThat(nmv.currency())
                .as("currency for raw=%s", raw)
                .isEqualTo(expectedCurrency);
    }

    private void assertMonetarySupported(GroundingValidationResult result, boolean expected) {
        boolean hasMonetary = result.allClaims().stream()
                .anyMatch(c -> c.type() == SensitiveClaimType.MONETARY_THRESHOLD);
        if (!hasMonetary) return;
        boolean anySupported = result.allClaims().stream()
                .filter(c -> c.type() == SensitiveClaimType.MONETARY_THRESHOLD)
                .anyMatch(SensitiveClaim::supported);
        assertThat(anySupported).isEqualTo(expected);
    }

    private void assertMonetaryUnsupported(GroundingValidationResult result) {
        boolean anyUnsupported = result.allClaims().stream()
                .filter(c -> c.type() == SensitiveClaimType.MONETARY_THRESHOLD)
                .anyMatch(c -> !c.supported());
        assertThat(anyUnsupported)
                .as("Expected at least one unsupported monetary claim but found none. Claims: %s",
                        result.allClaims())
                .isTrue();
    }
}
