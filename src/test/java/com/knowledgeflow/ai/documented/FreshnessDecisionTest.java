package com.knowledgeflow.ai.documented;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ai.grounding.AnswerSupportStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * C8 / ADR-008: agregação pelo pior estado das fontes usadas e efeito na decisão —
 * UNCERTAIN no máximo com limitações e parecer pelo menos SUGGESTED; STABLE_BUT_OLD com nota; CURRENT
 * não apaga risco M4B; OUTDATED mantém a política mais restritiva.
 */
class FreshnessDecisionTest {

    private final AnswerDecisionService decisionService = new AnswerDecisionService();
    private final AnswerProjectionService projection = new AnswerProjectionService();

    private static SourceEvidence legal(FreshnessStatus freshness, String core) {
        return new SourceEvidence("src-" + core, "Código — Artigo " + core, "LEGISLATION", AuthorityLevel.LEGAL,
                SourceRole.PRIMARY, SourceQuality.STRONG, core, core, SourceDiversity.MATERIAL_DIVERSITY, freshness,
                "ref " + core, "https://exemplo/" + core, true, true, false, false, false, List.of(), null,
                freshness != null ? List.of("freshness: " + freshness + " — motivo " + core) : List.of());
    }

    private AnswerDecision decide(List<SourceEvidence> sources) {
        return decisionService.decide(AnswerSupportStatus.SUPPORTED, false, sources, List.of(), null,
                FoundationGovernance.notAssessed());
    }

    @ParameterizedTest(name = "{0} + {1} → overall {2} / {3} / {4}")
    @CsvSource({
            "CURRENT,        CURRENT,        CURRENT,        CONSULTA_DOCUMENTADA,                NONE",
            "CURRENT,        STABLE_BUT_OLD, STABLE_BUT_OLD, CONSULTA_DOCUMENTADA,                NONE",
            "STABLE_BUT_OLD, CURRENT,        STABLE_BUT_OLD, CONSULTA_DOCUMENTADA,                NONE",
            "CURRENT,        UNCERTAIN,      UNCERTAIN,      CONSULTA_DOCUMENTADA_COM_LIMITACOES, SUGGESTED",
            "UNCERTAIN,      CURRENT,        UNCERTAIN,      CONSULTA_DOCUMENTADA_COM_LIMITACOES, SUGGESTED",
            "CURRENT,        OUTDATED,       OUTDATED,       CONSULTA_DOCUMENTADA_COM_LIMITACOES, SUGGESTED",
            "OUTDATED,       CURRENT,        OUTDATED,       CONSULTA_DOCUMENTADA_COM_LIMITACOES, SUGGESTED",
            "STABLE_BUT_OLD, UNCERTAIN,      UNCERTAIN,      CONSULTA_DOCUMENTADA_COM_LIMITACOES, SUGGESTED",
            "UNCERTAIN,      STABLE_BUT_OLD, UNCERTAIN,      CONSULTA_DOCUMENTADA_COM_LIMITACOES, SUGGESTED",
            "UNCERTAIN,      OUTDATED,       OUTDATED,       CONSULTA_DOCUMENTADA_COM_LIMITACOES, SUGGESTED"
    })
    void worstUsedSourceWins_inAnyOrder(FreshnessStatus a, FreshnessStatus b, FreshnessStatus overall,
            AnswerType type, ParecerRequirement parecer) {
        AnswerDecision d = decide(List.of(legal(a, "1"), legal(b, "2")));

        assertThat(d.overallFreshnessStatus()).isEqualTo(overall);
        assertThat(d.answerType()).isEqualTo(type);
        assertThat(d.parecerRequirement()).isEqualTo(parecer);
    }

    @Test
    @DisplayName("UNCERTAIN: com limitações, SUGGESTED, limitação explícita de actualidade e próximo passo de confirmação")
    void uncertain_isLimited_withExplicitLimitationAndNextStep() {
        AnswerDecision d = decide(List.of(legal(FreshnessStatus.UNCERTAIN, "1")));

        assertThat(d.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(d.parecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
        assertThat(d.limitations()).anyMatch(l -> l.contains("actualidade das fontes não está confirmada"));
        assertThat(d.nextSteps()).anyMatch(s -> s.contains("Confirmar a legislação"));
    }

    @Test
    @DisplayName("Actualidade ausente numa fonte = UNCERTAIN (fail-safe)")
    void missingFreshness_isUncertain() {
        AnswerDecision d = decide(List.of(legal(null, "1")));
        assertThat(d.overallFreshnessStatus()).isEqualTo(FreshnessStatus.UNCERTAIN);
        assertThat(d.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
    }

    @Test
    @DisplayName("STABLE_BUT_OLD: mantém a forma, com nota de antiguidade/estabilidade")
    void stable_keepsType_withNote() {
        AnswerDecision d = decide(List.of(legal(FreshnessStatus.STABLE_BUT_OLD, "1")));
        assertThat(d.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA);
        assertThat(d.limitations()).anyMatch(l -> l.contains("matéria estável"));
    }

    @Test
    @DisplayName("CURRENT: consulta documentada limpa, sem limitação nem passo de confirmação de actualidade")
    void current_isClean() {
        AnswerDecision d = decide(List.of(legal(FreshnessStatus.CURRENT, "1")));
        assertThat(d.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA);
        assertThat(d.parecerRequirement()).isEqualTo(ParecerRequirement.NONE);
        assertThat(d.limitations()).noneMatch(l -> l.toLowerCase().contains("actualidade"));
        assertThat(d.nextSteps()).noneMatch(s -> s.contains("Confirmar a legislação"));
    }

    @Test
    @DisplayName("CURRENT não apaga o M4B: HIGH, CRITICAL e requiresHumanValidation continuam a aplicar-se")
    void current_doesNotEraseM4b() {
        List<SourceEvidence> current = List.of(legal(FreshnessStatus.CURRENT, "1"));
        UUID qa = UUID.randomUUID();
        FoundationGovernance high = FoundationGovernance.of(List.of(
                new FoundationGovernance.FoundationRisk(qa, "x", KnowledgeRiskLevel.HIGH, false, null)));
        FoundationGovernance critical = FoundationGovernance.of(List.of(
                new FoundationGovernance.FoundationRisk(qa, "x", KnowledgeRiskLevel.CRITICAL, false, null)));
        FoundationGovernance lowRhv = FoundationGovernance.of(List.of(
                new FoundationGovernance.FoundationRisk(qa, "x", KnowledgeRiskLevel.LOW, true, null)));

        AnswerDecision h = decisionService.decide(AnswerSupportStatus.SUPPORTED, false, current, List.of(), null, high);
        assertThat(h.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(h.parecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);

        AnswerDecision c = decisionService.decide(AnswerSupportStatus.SUPPORTED, false, current, List.of(), null, critical);
        assertThat(c.answerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(c.parecerRequirement()).isEqualTo(ParecerRequirement.REQUIRED);

        AnswerDecision l = decisionService.decide(AnswerSupportStatus.SUPPORTED, false, current, List.of(), null, lowRhv);
        assertThat(l.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA);
        assertThat(l.parecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
    }

    @Test
    @DisplayName("OUTDATED: a política mais restritiva existente mantém-se (aviso, limitações, SUGGESTED)")
    void outdated_keepsRestrictivePolicy() {
        AnswerDecision d = decide(List.of(legal(FreshnessStatus.OUTDATED, "1")));
        assertThat(d.overallFreshnessStatus()).isEqualTo(FreshnessStatus.OUTDATED);
        assertThat(d.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(d.parecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
        assertThat(d.warnings()).anyMatch(w -> w.contains("desactualizada"));
    }

    @Test
    @DisplayName("Sem fontes usadas: regra de suporte anterior (não há fonte de onde derivar actualidade)")
    void noSources_keepsSupportRule() {
        AnswerDecision d = decide(List.of());
        assertThat(d.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA);
        assertThat(d.overallFreshnessStatus()).isEqualTo(FreshnessStatus.UNCERTAIN);
    }

    @ParameterizedTest(name = "projecção {0}: forma, parecer e actualidade das fontes preservados; motivo interno só em INTERNAL")
    @CsvSource({"INTERNAL", "DEMO", "EXTERNAL"})
    void projection_preservesFreshnessDecision(VisibilityLevel level) {
        List<SourceEvidence> sources = new ArrayList<>(List.of(legal(FreshnessStatus.CURRENT, "1"), legal(FreshnessStatus.UNCERTAIN, "2")));
        AnswerDecision d = decide(sources);
        DocumentedTaxiaAnswer answer = new DocumentedTaxiaAnswer("id", "P?", null, "R.", "R.", d.answerType(),
                AnswerSupportStatus.SUPPORTED, null, d.parecerRequirement(), VisibilityLevel.INTERNAL,
                d.overallFreshnessStatus(), d.confidenceSummary(), d.limitations(), List.of(), List.of(),
                d.sourceSummary(), sources, d.warnings(), d.nextSteps(),
                new InternalDiagnosticsBuilder().build(AnswerSupportStatus.SUPPORTED, false, sources, d, null));

        AnswerProjection p = projection.project(answer, level);
        assertThat(p.visibleAnswerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(p.visibleParecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
        assertThat(p.visibleSources()).extracting(SourceEvidence::freshnessStatus)
                .containsExactly(FreshnessStatus.CURRENT, FreshnessStatus.UNCERTAIN);
        assertThat(p.visibleSources()).allSatisfy(s -> assertThat(s.notesInternal()).isEmpty());
        if (level == VisibilityLevel.INTERNAL) {
            assertThat(answer.internalDiagnostics().freshnessSignals())
                    .anyMatch(s -> s.contains("overallFreshnessStatus=UNCERTAIN"))
                    .anyMatch(s -> s.contains("motivo 1"));
        }
    }
}
