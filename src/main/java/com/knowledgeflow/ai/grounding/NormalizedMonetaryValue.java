package com.knowledgeflow.ai.grounding;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Canonical representation of a monetary value (amount + currency ISO code).
 * Equality uses {@link BigDecimal#compareTo} so that 650000 and 650000.00 are equal.
 */
public record NormalizedMonetaryValue(BigDecimal amount, String currency) {

    public static final String EUR = "EUR";
    public static final String UNKNOWN = "UNKNOWN";

    public NormalizedMonetaryValue {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }

    /**
     * Two values are equivalent when their amounts compare equal and their currencies match
     * (or at least one currency is UNKNOWN, allowing lenient matching when the currency
     * was not explicit in the text).
     */
    public boolean sameAs(NormalizedMonetaryValue other) {
        if (other == null) return false;
        if (this.amount.compareTo(other.amount) != 0) return false;
        if (UNKNOWN.equals(this.currency) || UNKNOWN.equals(other.currency)) return true;
        return this.currency.equals(other.currency);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }
}
