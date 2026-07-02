package com.knowledgeflow.ai.grounding;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses raw monetary expressions (e.g. "650 000 €", "€ 1.234,56", "650000 EUR")
 * into a canonical {@link NormalizedMonetaryValue}.
 */
public class MonetaryValueNormalizer {

    private static final Pattern CURRENCY_PATTERN =
            Pattern.compile("(?:€|\\bEUR\\b|\\beuros?\\b)", Pattern.CASE_INSENSITIVE);

    private MonetaryValueNormalizer() {}

    /**
     * Normalizes a raw monetary match to its canonical form.
     * Returns empty if the numeric part cannot be parsed.
     */
    public static Optional<NormalizedMonetaryValue> normalize(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();

        String lower = raw.trim().toLowerCase();
        String currency = lower.matches(".*(?:€|\\beur(?:os?)?\\b).*")
                ? NormalizedMonetaryValue.EUR
                : NormalizedMonetaryValue.UNKNOWN;

        String numeric = CURRENCY_PATTERN.matcher(raw.trim()).replaceAll("").trim();
        try {
            BigDecimal amount = parseAmount(numeric);
            return Optional.of(new NormalizedMonetaryValue(amount, currency));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Parses the pure numeric portion of a monetary string.
     * <p>
     * Disambiguation rules:
     * <ul>
     *   <li>Both dot and comma present → rightmost is the decimal separator.</li>
     *   <li>Only dot → part after last dot is exactly 3 digits? thousands; else decimal.</li>
     *   <li>Only comma → part after last comma is 1–2 digits? decimal; else thousands.</li>
     *   <li>Space → always thousands separator.</li>
     * </ul>
     */
    static BigDecimal parseAmount(String numeric) {
        String s = numeric.trim();

        boolean hasDot   = s.contains(".");
        boolean hasComma = s.contains(",");

        if (hasDot && hasComma) {
            int lastDot   = s.lastIndexOf('.');
            int lastComma = s.lastIndexOf(',');
            if (lastComma > lastDot) {
                // PT format: 1.234.567,89 — dots = thousands, comma = decimal
                s = s.replace(".", "").replace(" ", "").replace(",", ".");
            } else {
                // Anglo-Saxon: 1,234,567.89 — commas = thousands, dot = decimal
                s = s.replace(",", "").replace(" ", "");
            }
        } else if (hasDot) {
            String afterDot = s.substring(s.lastIndexOf('.') + 1);
            if (afterDot.length() == 3) {
                // Thousands dot: "650.000"
                s = s.replace(".", "").replace(" ", "");
            } else {
                // Decimal dot: "650.50"
                s = s.replace(" ", "");
            }
        } else if (hasComma) {
            String afterComma = s.substring(s.lastIndexOf(',') + 1);
            if (afterComma.length() <= 2) {
                // Decimal comma: "650000,50"
                s = s.replace(" ", "").replace(",", ".");
            } else {
                // Thousands comma: "1,234"
                s = s.replace(",", "").replace(" ", "");
            }
        } else {
            // Only digits and spaces — spaces are thousands separators
            s = s.replace(" ", "");
        }

        return new BigDecimal(s);
    }
}
