package com.knowledgeflow.ai.documented;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ai.grounding.AnswerSupportStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Testes do {@link InternalDiagnosticsBuilder} (tarefa D11).
 *
 * <p>Verifica que o diagnóstico interno descreve o caminho técnico até à conclusão
 * ({@link AnswerDecisionService}) sem o recalcular, sem inventar risco agregado e, sobretudo,
 * <strong>sem nunca registar valores sensíveis</strong> (títulos, referências, excertos, notas
 * internas, identificadores técnicos). Compõe o {@link AnswerDecisionService} real para que os
 * sinais reflictam a decisão verdadeira, não uma regra inventada.
 */
class InternalDiagnosticsBuilderTest {

    private final InternalDiagnosticsBuilder builder = new InternalDiagnosticsBuilder();
    private final AnswerDecisionService decisionService = new AnswerDecisionService();

    private SourceEvidence source(
            AuthorityLevel authority, SourceRole role, SourceQuality quality,
            SourceDiversity diversity, FreshnessStatus freshness, String core) {
        return new SourceEvidence(
                "src-" + core, "Título " + core, "TIPO",
                authority, role, quality,
                core, core, diversity, freshness,
                "ref " + core, "https://exemplo/" + core,
                true, true, false, false,
                role == SourceRole.DERIVATIVE_REPLICATED,
                List.of("src-rel"), "Excerto sensível.", List.of("Nota interna sensível."));
    }

    private InternalDiagnostics build(
            AnswerSupportStatus status, boolean requiresHumanValidation,
            List<SourceEvidence> sources, KnowledgeRiskLevel aggregatedRiskLevel) {
        AnswerDecision decision = decisionService.decide(
                status, requiresHumanValidation, sources, null, null);
        return builder.build(status, requiresHumanValidation, sources, decision, aggregatedRiskLevel);
    }

    /** Achata todos os sinais num único texto para verificar ausência de valores sensíveis. */
    private String flatten(InternalDiagnostics d) {
        List<String> all = new ArrayList<>();
        all.addAll(d.decisionSignals());
        all.addAll(d.sourceSignals());
        all.addAll(d.riskSignals());
        all.addAll(d.freshnessSignals());
        all.addAll(d.diversitySignals());
        all.addAll(d.projectionSignals());
        all.addAll(d.hiddenForExternal());
        all.addAll(d.warningsInternal());
        return String.join("|", all);
    }

    @Test
    void strongLegalSource_observesCleanDecision_withoutRecalculating() {
        SourceEvidence legal = source(
                AuthorityLevel.LEGAL, SourceRole.PRIMARY, SourceQuality.STRONG,
                SourceDiversity.MATERIAL_DIVERSITY, FreshnessStatus.UNCERTAIN, "civa");

        InternalDiagnostics d = build(AnswerSupportStatus.SUPPORTED, false, List.of(legal), null);

        assertThat(d.decisionSignals()).contains(
                "supportStatus=SUPPORTED",
                "answerType=CONSULTA_DOCUMENTADA",
                "parecerRequirement=NONE");
        // Consulta limpa: sem sinal de rebaixamento.
        assertThat(d.decisionSignals())
                .noneMatch(s -> s.contains("rebaixado"));
        assertThat(d.sourceSignals()).contains("fontesUsadas=1", "fonte oficial/legal presente");
        assertThat(d.diversitySignals()).contains("diversidade material aparente");
    }

    @Test
    void supportedButWeakSources_registerDowngradeAndInternalWarning() {
        SourceEvidence weak = source(
                AuthorityLevel.EXTERNAL_NON_OFFICIAL, SourceRole.PRIMARY, SourceQuality.WEAK,
                SourceDiversity.MIXED_OR_UNCLEAR, FreshnessStatus.UNCERTAIN, "blogue");

        InternalDiagnostics d = build(AnswerSupportStatus.SUPPORTED, false, List.of(weak), null);

        assertThat(d.decisionSignals()).contains("answerType=CONSULTA_DOCUMENTADA_COM_LIMITACOES");
        assertThat(d.decisionSignals())
                .anyMatch(s -> s.contains("rebaixado para consulta com limitações"));
        assertThat(d.sourceSignals()).contains("apenas fontes de autoridade limitada");
        assertThat(d.warningsInternal())
                .anyMatch(w -> w.contains("não deve assentar apenas em fontes não oficiais"));
    }

    @Test
    void outdatedSource_flagsFreshnessAndHistoricalWarning() {
        SourceEvidence outdated = source(
                AuthorityLevel.LEGAL, SourceRole.PRIMARY, SourceQuality.STRONG,
                SourceDiversity.MATERIAL_DIVERSITY, FreshnessStatus.OUTDATED, "antigo");

        InternalDiagnostics d = build(AnswerSupportStatus.SUPPORTED, false, List.of(outdated), null);

        assertThat(d.freshnessSignals()).contains(
                "overallFreshnessStatus=OUTDATED",
                "fonte desactualizada assinalada por marcador textual",
                "sem verificação online de actualidade");
        assertThat(d.warningsInternal())
                .anyMatch(w -> w.contains("enquadramento histórico"));
    }

    @Test
    void twoDerivativeSameCoreSources_flagEchoAndLackOfDiversity() {
        SourceEvidence a = source(
                AuthorityLevel.OFFICIAL_ADMINISTRATIVE, SourceRole.DERIVATIVE_REPLICATED,
                SourceQuality.ADEQUATE, SourceDiversity.SAME_CORE, FreshnessStatus.UNCERTAIN, "nucleo");
        SourceEvidence b = source(
                AuthorityLevel.OFFICIAL_ADMINISTRATIVE, SourceRole.DERIVATIVE_REPLICATED,
                SourceQuality.ADEQUATE, SourceDiversity.SAME_CORE, FreshnessStatus.UNCERTAIN, "nucleo");

        InternalDiagnostics d = build(AnswerSupportStatus.SUPPORTED, false, List.of(a, b), null);

        assertThat(d.sourceSignals()).contains("todas as fontes são derivadas/replicadas");
        assertThat(d.diversitySignals()).contains(
                "fontes do mesmo núcleo (SAME_CORE)",
                "múltiplas fontes sem diversidade material confirmada");
        assertThat(d.warningsInternal())
                .anyMatch(w -> w.contains("eco documental"));
    }

    @Test
    void limitAnswer_isMarkedAsFramingNotError() {
        InternalDiagnostics d = build(
                AnswerSupportStatus.INSUFFICIENT_CONTEXT, false, List.of(), null);

        assertThat(d.decisionSignals()).contains("answerType=RESPOSTA_LIMITE");
        assertThat(d.decisionSignals())
                .anyMatch(s -> s.contains("não é erro"));
        assertThat(d.sourceSignals()).contains("sem fontes documentais recuperadas");
        assertThat(d.warningsInternal())
                .anyMatch(w -> w.contains("resposta-limite"));
    }

    @Test
    void aggregatedRiskLevel_whenPresent_isNamed_otherwiseMarkedAbsent() {
        SourceEvidence legal = source(
                AuthorityLevel.LEGAL, SourceRole.PRIMARY, SourceQuality.STRONG,
                SourceDiversity.MATERIAL_DIVERSITY, FreshnessStatus.UNCERTAIN, "civa");

        InternalDiagnostics absent = build(AnswerSupportStatus.SUPPORTED, false, List.of(legal), null);
        assertThat(absent.riskSignals()).anyMatch(s -> s.contains("aggregatedRiskLevel ausente"));

        InternalDiagnostics present = build(
                AnswerSupportStatus.SUPPORTED, false, List.of(legal), KnowledgeRiskLevel.HIGH);
        assertThat(present.riskSignals()).contains("aggregatedRiskLevel=HIGH");
    }

    @Test
    void neverLeaksSensitiveValues_onlyNamesCountsAndEnums() {
        SourceEvidence rich = source(
                AuthorityLevel.LEGAL, SourceRole.PRIMARY, SourceQuality.STRONG,
                SourceDiversity.MATERIAL_DIVERSITY, FreshnessStatus.UNCERTAIN, "sensivel");

        InternalDiagnostics d = build(AnswerSupportStatus.SUPPORTED, true, List.of(rich), null);

        String flat = flatten(d);
        assertThat(flat)
                .doesNotContain("Nota interna sensível.")
                .doesNotContain("Excerto sensível.")
                .doesNotContain("Título sensivel")
                .doesNotContain("src-sensivel")
                .doesNotContain("ref sensivel")
                .doesNotContain("https://exemplo/");
    }

    @Test
    void nullDecision_yieldsEmptyDiagnostics_withoutThrowing() {
        InternalDiagnostics d = builder.build(
                AnswerSupportStatus.SUPPORTED, false, List.of(), null, null);

        assertThat(d).isNotNull();
        assertThat(d.decisionSignals()).isEmpty();
        assertThat(d.sourceSignals()).isEmpty();
        assertThat(d.warningsInternal()).isEmpty();
    }

    @Test
    void requiresHumanValidation_addsInternalHumanReviewCaution() {
        InternalDiagnostics d = build(
                AnswerSupportStatus.REQUIRES_HUMAN_REVIEW, true, List.of(), null);

        assertThat(d.warningsInternal())
                .anyMatch(w -> w.contains("apreciação humana"));
        assertThat(d.decisionSignals()).contains("requiresHumanValidation=true");
    }
}
