package com.knowledgeflow.research.source;

import java.util.Locale;
import java.util.Objects;

/**
 * Referência canónica a um artigo de um código tributário: {@code CIRS 78.º-A}.
 * {@link #key()} identifica a referência na evidência e na memória crítica ({@code CIRS:78-A}).
 */
public record LegislationReference(String code, int article, String suffix) {

    public LegislationReference {
        Objects.requireNonNull(code, "code");
        if (article <= 0) throw new IllegalArgumentException("article must be positive");
        code = code.toUpperCase(Locale.ROOT);
        suffix = suffix == null || suffix.isBlank() ? null : suffix.toUpperCase(Locale.ROOT);
    }

    public String key() {
        return code + ":" + article + (suffix == null ? "" : "-" + suffix);
    }

    /** Número tal como aparece no cabeçalho oficial: {@code 78.º-A}. */
    public String articleLabel() {
        return article + ".º" + (suffix == null ? "" : "-" + suffix);
    }

    public String display() {
        return "artigo " + articleLabel() + " do " + code;
    }
}
