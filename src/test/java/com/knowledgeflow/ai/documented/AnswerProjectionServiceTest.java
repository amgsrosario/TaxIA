package com.knowledgeflow.ai.documented;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ai.grounding.AnswerSupportStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Testes da projecção mínima, determinística e conservadora {@link AnswerProjectionService}
 * (tarefa D7).
 *
 * <p>Valida que a projecção transforma só a apresentação por {@link VisibilityLevel}: oculta
 * bastidores em {@code EXTERNAL}/{@code DEMO}, preserva diagnóstico moderado em
 * {@code INTERNAL}, preserva tudo em {@code CURATION_ONLY}, e nunca recalcula
 * {@link AnswerType}/{@link ParecerRequirement} (regras 24–26).
 */
class AnswerProjectionServiceTest {

    private final AnswerProjectionService service = new AnswerProjectionService();

    private SourceEvidence richSource() {
        return new SourceEvidence(
                "src-123", "CIVA — Regime Geral", "LEGISLATION",
                AuthorityLevel.LEGAL, SourceRole.PRIMARY, SourceQuality.STRONG,
                "civa art. 18.º", "civa art. 18.º", SourceDiversity.MATERIAL_DIVERSITY,
                FreshnessStatus.UNCERTAIN, "CIVA art. 18.º", "https://info.portaldasfinancas.gov.pt",
                true, true, false, false, false,
                List.of("src-999"), "Excerto de suporte.", List.of("Nota interna sensível."));
    }

    private DocumentedTaxiaAnswer answer(
            AnswerType answerType, ParecerRequirement parecer, VisibilityLevel visibility,
            String shortAnswer, String technicalAnswer, List<SourceEvidence> sources) {
        return new DocumentedTaxiaAnswer(
                "ans-1", "Pergunta?", null, shortAnswer, technicalAnswer,
                answerType, AnswerSupportStatus.SUPPORTED, null, parecer, visibility,
                FreshnessStatus.UNCERTAIN, "Resumo de confiança.",
                List.of("Limitação relevante."), List.of(), List.of(),
                "Suporte documental.", sources, List.of("Aviso relevante."),
                List.of("Confirmar no caso concreto."), internalDiagnostics());
    }

    private InternalDiagnostics internalDiagnostics() {
        return new InternalDiagnostics(
                List.of("answerType=CONSULTA_DOCUMENTADA"),
                List.of("fonte oficial/legal presente"),
                List.of("aggregatedRiskLevel ausente (sem risco agregado calculado)"),
                List.of("overallFreshnessStatus=UNCERTAIN"),
                List.of("diversidade material aparente"),
                List.of("EXTERNAL/DEMO ocultam o diagnóstico interno e os bastidores"),
                List.of("internalDiagnostics", "notesInternal", "technicalIdentifiers"),
                List.of("sinalizada necessidade de apreciação humana"));
    }

    private DocumentedTaxiaAnswer supportedAnswer(VisibilityLevel visibility) {
        return answer(AnswerType.CONSULTA_DOCUMENTADA, ParecerRequirement.NONE, visibility,
                "Resposta curta.", "Resposta técnica.", List.of(richSource()));
    }

    // --- EXTERNAL / DEMO ---

    @Test
    void external_hidesInternalSourceFields_andInternalDiagnostics() {
        AnswerProjection p = service.project(supportedAnswer(null), VisibilityLevel.EXTERNAL);

        assertThat(p.targetVisibilityLevel()).isEqualTo(VisibilityLevel.EXTERNAL);
        SourceEvidence s = p.visibleSources().get(0);
        // Bastidores ocultados.
        assertThat(s.sourceCore()).isNull();
        assertThat(s.sourceDiversityGroup()).isNull();
        assertThat(s.sourceDiversity()).isNull();
        assertThat(s.notesInternal()).isEmpty();
        assertThat(s.sourceId()).isNull();
        assertThat(s.excerpt()).isNull();
        assertThat(s.relatedSources()).isEmpty();
        assertThat(s.authorityLevel()).isNull();
        // Campos profissionais preservados.
        assertThat(s.title()).isEqualTo("CIVA — Regime Geral");
        assertThat(s.legalReference()).isEqualTo("CIVA art. 18.º");
        assertThat(s.sourceRole()).isEqualTo(SourceRole.PRIMARY);
        assertThat(s.sourceQuality()).isEqualTo(SourceQuality.STRONG);
        assertThat(s.freshnessStatus()).isEqualTo(FreshnessStatus.UNCERTAIN);
        // hiddenDiagnostics regista tipos ocultados, sem valores sensíveis.
        assertThat(p.hiddenDiagnostics())
                .contains("internalDiagnostics", "sourceCore", "notesInternal", "technicalIdentifiers");
        assertThat(String.join("|", p.hiddenDiagnostics())).doesNotContain("Nota interna sensível.");
    }

    @Test
    void externalAndDemo_neverCarryInternalDiagnosticsValues() {
        // A AnswerProjection não tem campo internalDiagnostics; o conteúdo do diagnóstico
        // (sinais/cautelas internas) nunca deve aparecer em nenhuma superfície visível.
        for (VisibilityLevel level : List.of(VisibilityLevel.EXTERNAL, VisibilityLevel.DEMO)) {
            AnswerProjection p = service.project(supportedAnswer(null), level);
            String visibleSurface = String.join("|",
                    p.visibleAnswer(),
                    String.join("|", p.visibleLimitations()),
                    String.join("|", p.visibleWarnings()),
                    String.join("|", p.hiddenDiagnostics()),
                    String.join("|", p.projectionRulesApplied()));
            assertThat(visibleSurface)
                    .doesNotContain("sinalizada necessidade de apreciação humana")
                    .doesNotContain("aggregatedRiskLevel ausente")
                    .doesNotContain("diversidade material aparente");
            // hiddenDiagnostics apenas nomeia o que se oculta.
            assertThat(p.hiddenDiagnostics()).contains("internalDiagnostics");
        }
    }

    @Test
    void external_preservesLimitationsWarningsAndParecer() {
        AnswerProjection p = service.project(supportedAnswer(null), VisibilityLevel.EXTERNAL);

        assertThat(p.visibleLimitations()).contains("Limitação relevante.");
        assertThat(p.visibleWarnings()).contains("Aviso relevante.");
        assertThat(p.visibleParecerRequirement()).isEqualTo(ParecerRequirement.NONE);
        assertThat(p.visibleAnswerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA);
        assertThat(p.projectionRulesApplied())
                .contains("preserve:parecerRequirement", "preserve:limitations", "preserve:warnings");
    }

    @Test
    void demo_followsSameHidingAsExternal_andAppliesSameQualityRule() {
        AnswerProjection external = service.project(supportedAnswer(null), VisibilityLevel.EXTERNAL);
        AnswerProjection demo = service.project(supportedAnswer(null), VisibilityLevel.DEMO);

        SourceEvidence demoSource = demo.visibleSources().get(0);
        assertThat(demoSource.sourceCore()).isNull();
        assertThat(demoSource.notesInternal()).isEmpty();
        assertThat(demoSource.sourceId()).isNull();
        // Mesma ocultação que EXTERNAL.
        assertThat(demo.hiddenDiagnostics()).isEqualTo(external.hiddenDiagnostics());
        // Regra explícita de qualidade igual à externa.
        assertThat(demo.projectionRulesApplied()).contains("demo:same-quality-as-external");
        // Corpo visível idêntico (sem redução de qualidade).
        assertThat(demo.visibleAnswer()).isEqualTo(external.visibleAnswer());
    }

    // --- INTERNAL ---

    @Test
    void internal_preservesModerateDiagnostics_butHidesNotesInternal() {
        AnswerProjection p = service.project(supportedAnswer(null), VisibilityLevel.INTERNAL);

        SourceEvidence s = p.visibleSources().get(0);
        assertThat(s.sourceCore()).isEqualTo("civa art. 18.º");
        assertThat(s.sourceDiversity()).isEqualTo(SourceDiversity.MATERIAL_DIVERSITY);
        assertThat(s.usedInAnswer()).isTrue();
        assertThat(s.sourceId()).isEqualTo("src-123");
        assertThat(s.relatedSources()).containsExactly("src-999");
        // Notas internas ocultadas mesmo em INTERNAL (reservadas a CURATION_ONLY).
        assertThat(s.notesInternal()).isEmpty();
        assertThat(p.hiddenDiagnostics()).containsExactly("notesInternal");
    }

    // --- CURATION_ONLY ---

    @Test
    void curationOnly_preservesEverything_includingNotesInternal() {
        AnswerProjection p = service.project(supportedAnswer(null), VisibilityLevel.CURATION_ONLY);

        SourceEvidence s = p.visibleSources().get(0);
        assertThat(s.notesInternal()).containsExactly("Nota interna sensível.");
        assertThat(s.sourceCore()).isEqualTo("civa art. 18.º");
        assertThat(s.excerpt()).isEqualTo("Excerto de suporte.");
        assertThat(p.hiddenDiagnostics()).isEmpty();
        assertThat(p.projectionRulesApplied()).contains("preserve:all-backstage");
    }

    // --- decisão preservada ---

    @Test
    void projection_doesNotRecalculateAnswerTypeOrParecer() {
        DocumentedTaxiaAnswer limitAnswer = answer(
                AnswerType.RESPOSTA_LIMITE, ParecerRequirement.REQUIRED, VisibilityLevel.EXTERNAL,
                "Curta.", "Técnica.", List.of());

        AnswerProjection p = service.project(limitAnswer, VisibilityLevel.EXTERNAL);

        assertThat(p.visibleAnswerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(p.visibleParecerRequirement()).isEqualTo(ParecerRequirement.REQUIRED);
    }

    // --- visibleAnswer ---

    @Test
    void technicalAnswerPrevailsAsBody() {
        DocumentedTaxiaAnswer a = answer(
                AnswerType.CONSULTA_DOCUMENTADA, ParecerRequirement.NONE, VisibilityLevel.INTERNAL,
                "Curta.", "Técnica prevalece.", List.of());

        AnswerProjection p = service.project(a, VisibilityLevel.INTERNAL);

        assertThat(p.visibleAnswer()).isEqualTo("Técnica prevalece.");
    }

    @Test
    void shortAnswerUsedWhenTechnicalMissing() {
        DocumentedTaxiaAnswer a = answer(
                AnswerType.CONSULTA_DOCUMENTADA, ParecerRequirement.NONE, VisibilityLevel.INTERNAL,
                "Só curta.", null, List.of());

        AnswerProjection p = service.project(a, VisibilityLevel.INTERNAL);

        assertThat(p.visibleAnswer()).isEqualTo("Só curta.");
    }

    @Test
    void limitAnswerWithoutBody_producesPrudentMessage_notEmpty() {
        DocumentedTaxiaAnswer a = answer(
                AnswerType.RESPOSTA_LIMITE, ParecerRequirement.REQUIRED, VisibilityLevel.EXTERNAL,
                null, null, List.of());

        AnswerProjection p = service.project(a, VisibilityLevel.EXTERNAL);

        assertThat(p.visibleAnswer()).isEqualTo(
                "Não existe resposta documentada disponível para apresentação.");
        assertThat(p.visibleAnswer()).isNotBlank();
        // Resposta-limite não é não-resposta: encaminhamento continua visível.
        assertThat(p.visibleParecerRequirement()).isEqualTo(ParecerRequirement.REQUIRED);
    }

    // --- fallback de nível ---

    @Test
    void nullTarget_usesAnswerVisibilityLevel() {
        AnswerProjection p = service.project(supportedAnswer(VisibilityLevel.CURATION_ONLY), null);

        assertThat(p.targetVisibilityLevel()).isEqualTo(VisibilityLevel.CURATION_ONLY);
    }

    @Test
    void nullTargetAndNullAnswerVisibility_defaultsToInternal() {
        AnswerProjection p = service.project(supportedAnswer(null), null);

        assertThat(p.targetVisibilityLevel()).isEqualTo(VisibilityLevel.INTERNAL);
    }

    @Test
    void projectionRulesApplied_startWithVisibilityMarker() {
        AnswerProjection p = service.project(supportedAnswer(null), VisibilityLevel.EXTERNAL);

        assertThat(p.projectionRulesApplied()).first().isEqualTo("visibility:EXTERNAL");
        assertThat(p.projectionRulesApplied()).contains("preserve:answerType");
    }
}
