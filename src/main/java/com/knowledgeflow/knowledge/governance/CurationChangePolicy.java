package com.knowledgeflow.knowledge.governance;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Política central de alterações de curadoria (ADR-005): compara o estado actual com o pedido e
 * classifica cada campo alterado. Pura, sem dependências; a entidade aplica-a em todos os
 * caminhos (backoffice, ferramentas do piloto, re-import, serviços internos).
 *
 * <ul>
 *   <li>MATERIAL: respostas, pergunta normalizada, tema, subtema, jurisdição.</li>
 *   <li>Risco: subir é CONSERVATIVE, descer é EXPANSIVE.</li>
 *   <li>Validação humana obrigatória: false→true CONSERVATIVE, true→false EXPANSIVE.</li>
 *   <li>Validade: reduzir o período é CONSERVATIVE, alargar é EXPANSIVE; {@code null} significa
 *       sem limite (validFrom nulo = desde sempre, validTo nulo = sem fim).</li>
 *   <li>Notas: FREE.</li>
 * </ul>
 * Texto em branco é tratado como ausente e espaços nas extremidades são ignorados; na dúvida, a
 * classificação nunca é mais permissiva.
 */
public final class CurationChangePolicy {

    private CurationChangePolicy() {
    }

    public static CurationChanges classify(CurationSnapshot current, CurationSnapshot requested) {
        Map<String, CurationChangeKind> changes = new LinkedHashMap<>();
        text(changes, "normalizedQuestion", current.normalizedQuestion(), requested.normalizedQuestion());
        text(changes, "shortAnswer", current.shortAnswer(), requested.shortAnswer());
        text(changes, "technicalAnswer", current.technicalAnswer(), requested.technicalAnswer());
        if (current.topic() != requested.topic()) {
            changes.put("topic", CurationChangeKind.MATERIAL);
        }
        text(changes, "subtopic", current.subtopic(), requested.subtopic());
        text(changes, "jurisdiction", current.jurisdiction(), requested.jurisdiction());

        if (current.riskLevel() != requested.riskLevel()) {
            boolean raises = current.riskLevel() != null && requested.riskLevel() != null
                    && requested.riskLevel().ordinal() > current.riskLevel().ordinal();
            changes.put("riskLevel", raises ? CurationChangeKind.CONSERVATIVE : CurationChangeKind.EXPANSIVE);
        }
        if (current.requiresHumanValidation() != requested.requiresHumanValidation()) {
            changes.put("requiresHumanValidation", requested.requiresHumanValidation()
                    ? CurationChangeKind.CONSERVATIVE : CurationChangeKind.EXPANSIVE);
        }
        if (!Objects.equals(current.validFrom(), requested.validFrom())) {
            changes.put("validFrom", startRestricts(current.validFrom(), requested.validFrom())
                    ? CurationChangeKind.CONSERVATIVE : CurationChangeKind.EXPANSIVE);
        }
        if (!Objects.equals(current.validTo(), requested.validTo())) {
            changes.put("validTo", endRestricts(current.validTo(), requested.validTo())
                    ? CurationChangeKind.CONSERVATIVE : CurationChangeKind.EXPANSIVE);
        }
        text(changes, "notes", current.notes(), requested.notes(), CurationChangeKind.FREE);
        return new CurationChanges(changes);
    }

    /** Início mais tarde (ou passar a ter início) restringe; mais cedo ou sem início alarga. */
    static boolean startRestricts(LocalDate current, LocalDate requested) {
        if (requested == null) return false;
        return current == null || requested.isAfter(current);
    }

    /** Fim mais cedo (ou passar a ter fim) restringe; mais tarde ou sem fim alarga. */
    static boolean endRestricts(LocalDate current, LocalDate requested) {
        if (requested == null) return false;
        return current == null || requested.isBefore(current);
    }

    private static void text(Map<String, CurationChangeKind> changes, String field, String current, String requested) {
        text(changes, field, current, requested, CurationChangeKind.MATERIAL);
    }

    private static void text(Map<String, CurationChangeKind> changes, String field, String current,
            String requested, CurationChangeKind kind) {
        if (!Objects.equals(blankToNull(current), blankToNull(requested))) {
            changes.put(field, kind);
        }
    }

    /** Espaços nas extremidades não são alteração de conteúdo; em branco equivale a ausente. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
