package com.knowledgeflow.ai.documented;

import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import java.util.List;
import java.util.UUID;

/**
 * Risco e validação humana agregados dos fundamentos efectivamente usados numa resposta (M4B,
 * ADR-007; política C4). Só leitura, só em runtime.
 *
 * <ul>
 *   <li>{@code aggregatedRiskLevel}: o nível mais restritivo dos fundamentos com risco conhecido. Se
 *       algum fundamento tiver risco desconhecido, o agregado é {@code null} (RISK_UNKNOWN), excepto
 *       quando um fundamento é CRITICAL — o nível máximo, que nada pode exceder.</li>
 *   <li>{@code riskUnknown}: algum fundamento usado tem risco ausente/desconhecido (ou não foi
 *       possível lê-lo). Para a decisão automática equivale a HIGH (fail-closed), sem alterar a
 *       semântica: o risco continua identificado como desconhecido.</li>
 *   <li>{@code requiresHumanValidation}: basta um fundamento com {@code requiresHumanValidation=true}
 *       (ou com metadados ilegíveis) para o agregado ser {@code true}.</li>
 *   <li>{@code assessed}: {@code false} só nos caminhos legados sem informação de fundamentos (não
 *       usados pelo pipeline de produção); então nada é agregado e a decisão não muda.</li>
 * </ul>
 */
public record FoundationGovernance(
        boolean assessed,
        List<FoundationRisk> foundations,
        KnowledgeRiskLevel aggregatedRiskLevel,
        boolean riskUnknown,
        boolean requiresHumanValidation
) {

    /** Metadados de um fundamento usado; {@code riskLevel} é {@code null} quando o risco é desconhecido. */
    public record FoundationRisk(UUID sourceQaId, String title, KnowledgeRiskLevel riskLevel,
                                 boolean requiresHumanValidation, String unknownReason) {

        public boolean riskKnown() {
            return riskLevel != null;
        }
    }

    public FoundationGovernance {
        foundations = foundations != null ? List.copyOf(foundations) : List.of();
    }

    /** Sem informação de fundamentos (caminhos legados/teste): a decisão não é alterada. */
    public static FoundationGovernance notAssessed() {
        return new FoundationGovernance(false, List.of(), null, false, false);
    }

    /** Agrega os fundamentos usados segundo a C4 (mais restritivo) e a decisão M4B 1-B. */
    public static FoundationGovernance of(List<FoundationRisk> foundations) {
        List<FoundationRisk> safe = foundations != null ? List.copyOf(foundations) : List.of();
        KnowledgeRiskLevel maxKnown = null;
        boolean unknown = false;
        boolean humanValidation = false;
        for (FoundationRisk f : safe) {
            if (f.riskKnown()) {
                if (maxKnown == null || f.riskLevel().ordinal() > maxKnown.ordinal()) {
                    maxKnown = f.riskLevel();
                }
            } else {
                unknown = true;
            }
            humanValidation |= f.requiresHumanValidation();
        }
        KnowledgeRiskLevel aggregated = unknown && maxKnown != KnowledgeRiskLevel.CRITICAL ? null : maxKnown;
        return new FoundationGovernance(true, safe, aggregated, unknown, humanValidation);
    }

    /** Nível mais restritivo entre os fundamentos com risco conhecido ({@code null} se nenhum). */
    public KnowledgeRiskLevel maxKnownRiskLevel() {
        KnowledgeRiskLevel max = null;
        for (FoundationRisk f : foundations) {
            if (f.riskKnown() && (max == null || f.riskLevel().ordinal() > max.ordinal())) {
                max = f.riskLevel();
            }
        }
        return max;
    }

    /**
     * Nível usado pela decisão automática: o agregado, com o risco desconhecido tratado como HIGH
     * (fail-closed). {@code null} quando não há fundamentos (ex.: Resposta-limite antes do LLM).
     */
    public KnowledgeRiskLevel decisionRiskLevel() {
        if (aggregatedRiskLevel == KnowledgeRiskLevel.CRITICAL) {
            return KnowledgeRiskLevel.CRITICAL;
        }
        if (riskUnknown) {
            return KnowledgeRiskLevel.HIGH;
        }
        return aggregatedRiskLevel;
    }
}
