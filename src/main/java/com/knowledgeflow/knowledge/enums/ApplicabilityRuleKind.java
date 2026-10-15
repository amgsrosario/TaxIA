package com.knowledgeflow.knowledge.enums;

import java.util.Arrays;
import java.util.Optional;

/**
 * Tipo de regra de aplicabilidade de uma Q&amp;A (ADR-004, alargado pelo ADR-009).
 *
 * <ul>
 *   <li>{@link #EXCLUDES}: a Q&amp;A não cobre a situação; se a pergunta a mostrar, o candidato é
 *       rejeitado ({@code APPLICABILITY_EXCLUDED}).</li>
 *   <li>{@link #REQUIRES}: a Q&amp;A só se aplica quando a pergunta mostra a situação; se não a
 *       mostrar, o candidato é rejeitado ({@code APPLICABILITY_CONTEXT_MISSING}).</li>
 * </ul>
 * Ambos estreitam o âmbito: acrescentar é imediato; retirar alarga e segue a governação de remoção.
 */
public enum ApplicabilityRuleKind {
    EXCLUDES,
    REQUIRES;

    /** Código persistido → tipo; vazio para códigos desconhecidos. */
    public static Optional<ApplicabilityRuleKind> fromCode(String code) {
        return Arrays.stream(values()).filter(k -> k.name().equals(code)).findFirst();
    }
}
