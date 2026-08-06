package com.knowledgeflow.ai.documented;

import com.knowledgeflow.ai.grounding.AnswerSupportStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Construtor do {@link InternalDiagnostics} da resposta documentada (tarefa D11).
 *
 * <p>Recolhe, de forma <strong>determinística e conservadora</strong>, o caminho técnico até à
 * conclusão profissional já decidida pelo {@link AnswerDecisionService}. Reobserva os sinais
 * qualitativos das fontes (autoridade, qualidade, actualidade, diversidade, papel) apenas para
 * <em>descrever</em> a decisão — <strong>nunca</strong> para a recalcular (regras 22–24). O
 * {@link AnswerType} e o {@link ParecerRequirement} são lidos da {@link AnswerDecision}, não
 * reproduzidos aqui.
 *
 * <p>Nunca regista conteúdo sensível: só nomes de sinais, contagens e nomes de enumerados. Não
 * inclui chunks, scores em bruto, ranking, prompts, logs, notas internas, excertos, títulos,
 * referências nem identificadores técnicos (regras 19, 21). Não persiste (regra 16).
 */
@Service
public class InternalDiagnosticsBuilder {

    /**
     * Constrói o diagnóstico interno a partir da decisão já tomada e dos sinais das fontes.
     *
     * @param supportStatus estado de suporte do grounding (pode ser {@code null})
     * @param requiresHumanValidation sinalização de validação humana do grounding
     * @param sources fontes já avaliadas (D5); {@code null} é tratado como vazio
     * @param decision decisão consolidada (D6); observada, nunca recalculada
     * @param aggregatedRiskLevel risco agregado da resposta, se existir (habitualmente {@code null})
     * @return diagnóstico interno; nunca {@code null}
     */
    public InternalDiagnostics build(
            AnswerSupportStatus supportStatus,
            boolean requiresHumanValidation,
            List<SourceEvidence> sources,
            AnswerDecision decision,
            KnowledgeRiskLevel aggregatedRiskLevel) {

        if (decision == null) {
            return InternalDiagnostics.empty();
        }

        List<SourceEvidence> safeSources = sources != null ? sources : List.of();

        boolean hasSources = !safeSources.isEmpty();
        boolean hasStrongOrAdequate = anyQuality(safeSources, SourceQuality.STRONG, SourceQuality.ADEQUATE);
        boolean hasOnlyWeak = hasSources && safeSources.stream().allMatch(s ->
                s.sourceQuality() == SourceQuality.WEAK
                        || s.authorityLevel() == AuthorityLevel.EXTERNAL_NON_OFFICIAL);
        boolean hasOfficialOrLegal = safeSources.stream().anyMatch(this::isOfficialOrLegal);
        boolean hasOnlyDerivative = hasSources && safeSources.stream()
                .allMatch(s -> s.sourceRole() == SourceRole.DERIVATIVE_REPLICATED);
        boolean hasOutdated = anyFreshness(safeSources, FreshnessStatus.OUTDATED);
        boolean hasUncertainFreshness = anyFreshness(safeSources, FreshnessStatus.UNCERTAIN);
        boolean hasMaterialDiversity = anyDiversity(safeSources, SourceDiversity.MATERIAL_DIVERSITY);
        boolean hasSameCore = anyDiversity(safeSources, SourceDiversity.SAME_CORE);
        boolean hasMixedOrUnclear = anyDiversity(safeSources, SourceDiversity.MIXED_OR_UNCLEAR);
        boolean lacksMaterialDiversity = safeSources.size() >= 2 && !hasMaterialDiversity;
        boolean isLimitAnswer = decision.answerType() == AnswerType.RESPOSTA_LIMITE;

        return new InternalDiagnostics(
                decisionSignals(supportStatus, requiresHumanValidation, decision, isLimitAnswer),
                sourceSignals(safeSources.size(), hasSources, hasOfficialOrLegal, hasStrongOrAdequate,
                        hasOnlyWeak, hasOnlyDerivative),
                riskSignals(aggregatedRiskLevel),
                freshnessSignals(decision, hasOutdated, hasUncertainFreshness),
                diversitySignals(hasMaterialDiversity, hasSameCore, hasMixedOrUnclear, lacksMaterialDiversity),
                projectionSignals(),
                hiddenForExternal(),
                warningsInternal(requiresHumanValidation, hasOutdated, hasOnlyWeak, hasOnlyDerivative,
                        lacksMaterialDiversity, isLimitAnswer));
    }

    // --- sinais de decisão (lidos, nunca recalculados) ---

    private List<String> decisionSignals(
            AnswerSupportStatus supportStatus, boolean requiresHumanValidation,
            AnswerDecision decision, boolean isLimitAnswer) {
        List<String> signals = new ArrayList<>();
        signals.add("supportStatus=" + (supportStatus != null ? supportStatus.name() : "AUSENTE"));
        signals.add("requiresHumanValidation=" + requiresHumanValidation);
        signals.add("answerType=" + decision.answerType());
        signals.add("parecerRequirement=" + decision.parecerRequirement());
        if (supportStatus == AnswerSupportStatus.SUPPORTED
                && decision.answerType() == AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES) {
            signals.add("SUPPORTED rebaixado para consulta com limitações por fraqueza documental");
        }
        if (isLimitAnswer) {
            signals.add("resposta-limite é enquadramento sem conclusão fechada (não é erro)");
        }
        signals.add("decisão tomada no AnswerDecisionService; diagnóstico apenas observa");
        return List.copyOf(signals);
    }

    // --- sinais de fontes ---

    private List<String> sourceSignals(
            int count, boolean hasSources, boolean hasOfficialOrLegal, boolean hasStrongOrAdequate,
            boolean hasOnlyWeak, boolean hasOnlyDerivative) {
        List<String> signals = new ArrayList<>();
        signals.add("fontesUsadas=" + count);
        signals.add(hasOfficialOrLegal ? "fonte oficial/legal presente" : "sem fonte oficial/legal clara");
        if (hasStrongOrAdequate) {
            signals.add("pelo menos uma fonte forte/adequada");
        }
        if (hasOnlyWeak) {
            signals.add("apenas fontes de autoridade limitada");
        }
        if (hasOnlyDerivative) {
            signals.add("todas as fontes são derivadas/replicadas");
        }
        if (!hasSources) {
            signals.add("sem fontes documentais recuperadas");
        }
        return List.copyOf(signals);
    }

    // --- sinais de risco ---

    private List<String> riskSignals(KnowledgeRiskLevel aggregatedRiskLevel) {
        List<String> signals = new ArrayList<>();
        signals.add(aggregatedRiskLevel != null
                ? "aggregatedRiskLevel=" + aggregatedRiskLevel.name()
                : "aggregatedRiskLevel ausente (sem risco agregado calculado)");
        signals.add("riskLevel persistido da entidade não é usado como risco agregado");
        return List.copyOf(signals);
    }

    // --- sinais de actualidade ---

    private List<String> freshnessSignals(
            AnswerDecision decision, boolean hasOutdated, boolean hasUncertainFreshness) {
        List<String> signals = new ArrayList<>();
        signals.add("overallFreshnessStatus=" + decision.overallFreshnessStatus());
        if (hasOutdated) {
            signals.add("fonte desactualizada assinalada por marcador textual");
        }
        if (hasUncertainFreshness) {
            signals.add("actualidade incerta em pelo menos uma fonte");
        }
        signals.add("sem verificação online de actualidade");
        return List.copyOf(signals);
    }

    // --- sinais de diversidade ---

    private List<String> diversitySignals(
            boolean hasMaterialDiversity, boolean hasSameCore, boolean hasMixedOrUnclear,
            boolean lacksMaterialDiversity) {
        List<String> signals = new ArrayList<>();
        if (hasMaterialDiversity) {
            signals.add("diversidade material aparente");
        }
        if (hasSameCore) {
            signals.add("fontes do mesmo núcleo (SAME_CORE)");
        }
        if (hasMixedOrUnclear) {
            signals.add("diversidade mista ou pouco clara");
        }
        if (lacksMaterialDiversity) {
            signals.add("múltiplas fontes sem diversidade material confirmada");
        }
        if (signals.isEmpty()) {
            signals.add("diversidade não determinante nesta resposta");
        }
        return List.copyOf(signals);
    }

    // --- política de projecção ---

    private List<String> projectionSignals() {
        return List.of(
                "EXTERNAL/DEMO ocultam o diagnóstico interno e os bastidores",
                "INTERNAL preserva diagnóstico moderado",
                "CURATION_ONLY preserva bastidores completos",
                "diagnóstico apenas em runtime; não é persistido");
    }

    // --- campos ocultados a EXTERNAL/DEMO (só nomes/tipos, nunca valores) ---

    private List<String> hiddenForExternal() {
        return List.of(
                "internalDiagnostics",
                "aggregatedRiskLevel",
                "sourceId",
                "authorityLevel",
                "sourceCore",
                "sourceDiversityGroup",
                "sourceDiversity",
                "usedInAnswer",
                "derivativeOrReplicated",
                "relatedSources",
                "excerpt",
                "notesInternal",
                "scores",
                "ranking",
                "chunks",
                "prompts",
                "logs",
                "technicalIdentifiers");
    }

    // --- cautelas internas (sem valores sensíveis) ---

    private List<String> warningsInternal(
            boolean requiresHumanValidation, boolean hasOutdated, boolean hasOnlyWeak,
            boolean hasOnlyDerivative, boolean lacksMaterialDiversity, boolean isLimitAnswer) {
        List<String> warnings = new ArrayList<>();
        if (hasOutdated) {
            warnings.add("fonte desactualizada — usar só como enquadramento histórico");
        }
        if (hasOnlyWeak) {
            warnings.add("conclusão não deve assentar apenas em fontes não oficiais");
        }
        if (hasOnlyDerivative || lacksMaterialDiversity) {
            warnings.add("possível eco documental — diversidade material insuficiente");
        }
        if (requiresHumanValidation) {
            warnings.add("sinalizada necessidade de apreciação humana");
        }
        if (isLimitAnswer) {
            warnings.add("resposta-limite — não fechar conclusão sem suporte adicional");
        }
        return List.copyOf(warnings);
    }

    // --- helpers de observação (não decidem) ---

    private boolean isOfficialOrLegal(SourceEvidence s) {
        if (s.authorityLevel() == null) {
            return false;
        }
        return switch (s.authorityLevel()) {
            case LEGAL, OFFICIAL_FAQ, OFFICIAL_ADMINISTRATIVE, JURISPRUDENCE, OFFICIAL_COMPLEMENTARY -> true;
            case INTERNAL_CURATED, EXTERNAL_NON_OFFICIAL -> false;
        };
    }

    private boolean anyQuality(List<SourceEvidence> sources, SourceQuality a, SourceQuality b) {
        return sources.stream().anyMatch(s -> s.sourceQuality() == a || s.sourceQuality() == b);
    }

    private boolean anyFreshness(List<SourceEvidence> sources, FreshnessStatus status) {
        return sources.stream().anyMatch(s -> s.freshnessStatus() == status);
    }

    private boolean anyDiversity(List<SourceEvidence> sources, SourceDiversity diversity) {
        return sources.stream().anyMatch(s -> s.sourceDiversity() == diversity);
    }
}
