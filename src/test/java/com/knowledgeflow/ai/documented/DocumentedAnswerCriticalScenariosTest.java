package com.knowledgeflow.ai.documented;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ai.grounding.AnswerSource;
import com.knowledgeflow.ai.grounding.AnswerSupportStatus;
import com.knowledgeflow.ai.grounding.GroundedAIResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Cenários críticos da resposta profissional documentada (tarefa D10).
 *
 * <p>Ao contrário dos testes unitários por serviço, este ficheiro compõe os serviços
 * <strong>reais</strong> ({@link SourceAssessmentService} → {@link AnswerDecisionService} →
 * {@link DocumentedTaxiaAnswerMapper} → {@link AnswerProjectionService}, com
 * {@link VisibilityLevelResolver} onde faz sentido) e testa a <em>filosofia do produto</em>
 * (Decisões C1–C9 e o contrato D1–D9), não apenas a mecânica isolada de cada peça:
 *
 * <ul>
 *   <li>Resposta-limite é uma resposta de pleno direito, não erro nem silêncio;</li>
 *   <li>Pedido de parecer é encaminhamento estrutural, não falha técnica;</li>
 *   <li>fontes desactualizadas/fracas/derivadas não sustentam conclusão limpa;</li>
 *   <li>a projecção mostra/oculta, mas <strong>nunca</strong> recalcula a decisão;</li>
 *   <li>não se confunde {@code riskLevel} da entidade com risco agregado, nem
 *       {@code KnowledgeCurationStatus.OUTDATED} com {@link FreshnessStatus#OUTDATED}.</li>
 * </ul>
 *
 * <p>Os valores esperados reflectem o comportamento realmente decidido em D5/D6/D7 — não
 * forçam regras novas (rule 15).
 */
class DocumentedAnswerCriticalScenariosTest {

    private final SourceAssessmentService sourceAssessmentService = new SourceAssessmentService();
    private final AnswerDecisionService decisionService = new AnswerDecisionService();
    private final DocumentedTaxiaAnswerMapper mapper =
            new DocumentedTaxiaAnswerMapper(sourceAssessmentService, decisionService);
    private final AnswerProjectionService projectionService = new AnswerProjectionService();
    private final VisibilityLevelResolver visibilityLevelResolver = new VisibilityLevelResolver();

    // ── Helpers ──────────────────────────────────────────────────────────────

    private GroundedAIResponse grounded(
            String answer, AnswerSupportStatus status, List<AnswerSource> sources,
            boolean requiresHumanValidation) {
        return new GroundedAIResponse(
                answer, status, "Motivo", sources,
                List.of(), List.of(), requiresHumanValidation,
                requiresHumanValidation ? "Apreciação humana recomendada." : null,
                true, false, null, 0, "stub", "stub", 0, 0, 0L);
    }

    /** SourceEvidence composta manualmente para exercitar a decisão sem passar pelo assessor. */
    private SourceEvidence sourceEvidence(
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

    private DocumentedTaxiaAnswer documented(
            AnswerType answerType, ParecerRequirement parecer, VisibilityLevel visibility,
            List<SourceEvidence> sources) {
        return new DocumentedTaxiaAnswer(
                "ans-1", "Pergunta?", null, "Resposta curta.", "Resposta técnica.",
                answerType, AnswerSupportStatus.SUPPORTED, null, parecer, visibility,
                FreshnessStatus.UNCERTAIN, "Resumo de confiança.",
                List.of("Limitação relevante."), List.of(), List.of(),
                "Suporte documental.", sources, List.of("Aviso relevante."),
                List.of("Confirmar no caso concreto."),
                "supportReason=X; provider=stub; score=0.9");
    }

    // ── Cenário 1 — Consulta documentada com fonte forte ──────────────────────

    @Nested
    @DisplayName("Cenário 1 — consulta documentada com fonte forte")
    class StrongSource {

        @Test
        void supportedWithLegalSource_isCleanConsultaDocumentada_andProjectsProfessionally() {
            var source = new AnswerSource("Código do IRS — Artigo 41.º", "CIRS, art. 41.º", 0.95);
            DocumentedTaxiaAnswer answer = mapper.fromGroundedResponse(
                    "Como se determinam os rendimentos prediais?",
                    grounded("Os rendimentos prediais...", AnswerSupportStatus.SUPPORTED,
                            List.of(source), false));

            // Decisão limpa: fonte legal forte, sem sinais de fraqueza documental.
            assertThat(answer.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA);
            assertThat(answer.parecerRequirement()).isEqualTo(ParecerRequirement.NONE);

            SourceEvidence evidence = answer.sources().get(0);
            assertThat(evidence.authorityLevel()).isEqualTo(AuthorityLevel.LEGAL);
            assertThat(evidence.sourceQuality()).isEqualTo(SourceQuality.STRONG);

            // Projecção INTERNAL preserva diagnóstico; EXTERNAL apresenta produto profissional.
            AnswerProjection internal = projectionService.project(answer, VisibilityLevel.INTERNAL);
            assertThat(internal.visibleSources().get(0).authorityLevel()).isEqualTo(AuthorityLevel.LEGAL);

            AnswerProjection external = projectionService.project(answer, VisibilityLevel.EXTERNAL);
            assertThat(external.visibleAnswer()).isNotBlank();
            SourceEvidence externalSource = external.visibleSources().get(0);
            assertThat(externalSource.title()).isEqualTo("Código do IRS — Artigo 41.º");
            assertThat(externalSource.sourceQuality()).isEqualTo(SourceQuality.STRONG);
            assertThat(externalSource.authorityLevel()).isNull(); // bastidor ocultado
        }
    }

    // ── Cenário 2 — Fonte forte mas actualidade incerta → prudência adequada ──

    @Test
    @DisplayName("Cenário 2 — actualidade incerta preserva tipo mas assinala prudência")
    void strongSourceUncertainFreshness_keepsConsultaDocumentada_butFlagsPrudence() {
        // O assessor usa FreshnessStatus.UNCERTAIN por defeito (não inventa CURRENT).
        var source = new AnswerSource("Código do IVA — Artigo 18.º", "CIVA, art. 18.º", 0.9);
        DocumentedTaxiaAnswer answer = mapper.fromGroundedResponse(
                "Qual a taxa aplicável?",
                grounded("A taxa...", AnswerSupportStatus.SUPPORTED, List.of(source), false));

        assertThat(answer.freshnessStatus()).isEqualTo(FreshnessStatus.UNCERTAIN);
        // D6 preserva a consulta documentada limpa (a incerteza de actualidade, por si só,
        // não a rebaixa), mas a prudência aparece nas limitações e no resumo de fontes.
        assertThat(answer.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA);
        assertThat(answer.limitations())
                .anyMatch(l -> l.toLowerCase().contains("actualidade"));
        assertThat(answer.sourceSummary().toLowerCase()).contains("actualidade incerta");
    }

    // ── Cenário 3 / 13 — Resposta-limite não é erro nem não-resposta ──────────

    @Test
    @DisplayName("Cenário 3/13 — resposta-limite mantém corpo, limitações e encaminhamento")
    void insufficientContext_isRespostaLimite_notErrorNorEmpty() {
        var source = new AnswerSource("Enquadramento geral", "nota interna", 0.4);
        DocumentedTaxiaAnswer answer = mapper.fromGroundedResponse(
                "Pergunta sem suporte suficiente.",
                grounded("Enquadramento possível, sem conclusão fechada.",
                        AnswerSupportStatus.INSUFFICIENT_CONTEXT, List.of(source), false));

        assertThat(answer.answerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        // Não-resposta seria vazio: aqui há corpo e limitações explícitas.
        assertThat(answer.technicalAnswer()).isNotBlank();
        assertThat(answer.limitations()).isNotEmpty();
        assertThat(answer.parecerRequirement())
                .isIn(ParecerRequirement.SUGGESTED, ParecerRequirement.REQUIRED);

        // A projecção mostra a resposta (não a trata como erro).
        AnswerProjection external = projectionService.project(answer, VisibilityLevel.EXTERNAL);
        assertThat(external.visibleAnswer()).isNotBlank();
        assertThat(external.visibleAnswerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(external.visibleParecerRequirement()).isNotEqualTo(ParecerRequirement.NONE);
    }

    // ── Cenário 4 — Pedido de parecer não é falha técnica ─────────────────────

    @Test
    @DisplayName("Cenário 4 — revisão humana produz resposta com limitações, não excepção")
    void requiresHumanReview_isDocumentedWithLimitations_notTechnicalFailure() {
        DocumentedTaxiaAnswer answer = mapper.fromGroundedResponse(
                "Pergunta que pede apreciação humana.",
                grounded("Enquadramento prudente.", AnswerSupportStatus.REQUIRES_HUMAN_REVIEW,
                        List.of(), true));

        assertThat(answer.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(answer.parecerRequirement()).isNotEqualTo(ParecerRequirement.NONE);
        assertThat(answer.warnings()).isNotEmpty();
        assertThat(answer.nextSteps()).isNotEmpty();
        assertThat(answer.technicalAnswer()).isNotBlank();

        // Projecta sem excepção e permanece visível.
        AnswerProjection projection = projectionService.project(
                answer, visibilityLevelResolver.resolveForAdminAsk());
        assertThat(projection.visibleAnswer()).isNotBlank();
    }

    // ── Cenário 5 — Fonte OUTDATED não sustenta conclusão limpa ───────────────

    @Test
    @DisplayName("Cenário 5 — fonte desactualizada degrada para consulta com limitações + aviso")
    void outdatedSource_doesNotSupportCleanConclusion() {
        SourceEvidence outdated = sourceEvidence(
                AuthorityLevel.LEGAL, SourceRole.PRIMARY, SourceQuality.STRONG,
                SourceDiversity.MATERIAL_DIVERSITY, FreshnessStatus.OUTDATED, "civa-antigo");

        AnswerDecision decision = decisionService.decide(
                AnswerSupportStatus.SUPPORTED, false, List.of(outdated), null, null);

        assertThat(decision.answerType()).isNotEqualTo(AnswerType.CONSULTA_DOCUMENTADA);
        assertThat(decision.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        // Pelo menos SUGGESTED (a escala só sobe).
        assertThat(decision.parecerRequirement().ordinal())
                .isGreaterThanOrEqualTo(ParecerRequirement.SUGGESTED.ordinal());
        // A fonte desactualizada é tratada como histórico/contraste/alerta.
        assertThat(decision.warnings())
                .anyMatch(w -> w.toLowerCase().contains("desactualizada")
                        && w.toLowerCase().contains("histórico"));
    }

    // ── Cenário 6 — Fontes fracas/externas não sustentam conclusão limpa ──────

    @Test
    @DisplayName("Cenário 6 — fonte externa não oficial e fraca gera limitação e prudência")
    void weakExternalSource_doesNotSupportCleanConclusion() {
        SourceEvidence weak = sourceEvidence(
                AuthorityLevel.EXTERNAL_NON_OFFICIAL, SourceRole.PRIMARY, SourceQuality.WEAK,
                SourceDiversity.MIXED_OR_UNCLEAR, FreshnessStatus.UNCERTAIN, "blogue");

        AnswerDecision decision = decisionService.decide(
                AnswerSupportStatus.SUPPORTED, false, List.of(weak), null, null);

        assertThat(decision.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(decision.parecerRequirement().ordinal())
                .isGreaterThanOrEqualTo(ParecerRequirement.SUGGESTED.ordinal());
        assertThat(decision.limitations())
                .anyMatch(l -> l.toLowerCase().contains("autoridade limitada"));
        assertThat(decision.warnings())
                .anyMatch(w -> w.toLowerCase().contains("não oficiais"));
    }

    // ── Cenário 7 — Fontes derivadas/SAME_CORE não são diversidade material ───

    @Test
    @DisplayName("Cenário 7 — duas fontes derivadas do mesmo núcleo não contam como diversidade")
    void derivativeSameCoreSources_doNotCountAsMaterialDiversity() {
        SourceEvidence a = sourceEvidence(
                AuthorityLevel.OFFICIAL_ADMINISTRATIVE, SourceRole.DERIVATIVE_REPLICATED,
                SourceQuality.ADEQUATE, SourceDiversity.SAME_CORE, FreshnessStatus.UNCERTAIN, "nucleo");
        SourceEvidence b = sourceEvidence(
                AuthorityLevel.OFFICIAL_ADMINISTRATIVE, SourceRole.DERIVATIVE_REPLICATED,
                SourceQuality.ADEQUATE, SourceDiversity.SAME_CORE, FreshnessStatus.UNCERTAIN, "nucleo");

        AnswerDecision decision = decisionService.decide(
                AnswerSupportStatus.SUPPORTED, false, List.of(a, b), null, null);

        // Duas fontes, mas do mesmo núcleo: não é robustez real.
        assertThat(decision.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(decision.limitations())
                .anyMatch(l -> l.toLowerCase().contains("derivadas")
                        || l.toLowerCase().contains("diversidade material"));
    }

    // ── Cenário 8 — EXTERNAL/DEMO ocultam bastidores ──────────────────────────

    @Test
    @DisplayName("Cenário 8 — EXTERNAL e DEMO ocultam bastidores mas preservam prudência")
    void externalAndDemo_hideBackstage_butPreserveLimitations() {
        SourceEvidence rich = sourceEvidence(
                AuthorityLevel.LEGAL, SourceRole.PRIMARY, SourceQuality.STRONG,
                SourceDiversity.MATERIAL_DIVERSITY, FreshnessStatus.UNCERTAIN, "civa");
        DocumentedTaxiaAnswer answer = documented(
                AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES, ParecerRequirement.SUGGESTED,
                VisibilityLevel.INTERNAL, List.of(rich));

        AnswerProjection external = projectionService.project(answer, VisibilityLevel.EXTERNAL);
        AnswerProjection demo = projectionService.project(answer, VisibilityLevel.DEMO);

        for (AnswerProjection p : List.of(external, demo)) {
            SourceEvidence s = p.visibleSources().get(0);
            assertThat(s.sourceCore()).isNull();
            assertThat(s.sourceDiversityGroup()).isNull();
            assertThat(s.sourceDiversity()).isNull();
            assertThat(s.sourceId()).isNull();
            assertThat(s.excerpt()).isNull();
            assertThat(s.relatedSources()).isEmpty();
            assertThat(s.notesInternal()).isEmpty();
            // hiddenDiagnostics lista apenas nomes/tipos, nunca valores sensíveis.
            assertThat(String.join("|", p.hiddenDiagnostics()))
                    .doesNotContain("Nota interna sensível.", "Excerto sensível.");
            // Prudência preservada.
            assertThat(p.visibleLimitations()).isNotEmpty();
            assertThat(p.visibleWarnings()).isNotEmpty();
            assertThat(p.visibleParecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
        }
        // DEMO tem a mesma qualidade conceptual de EXTERNAL.
        assertThat(demo.hiddenDiagnostics()).isEqualTo(external.hiddenDiagnostics());
        assertThat(demo.visibleAnswer()).isEqualTo(external.visibleAnswer());
    }

    // ── Cenário 9 — INTERNAL/CURATION_ONLY preservam diagnóstico adequado ─────

    @Test
    @DisplayName("Cenário 9 — INTERNAL preserva diagnóstico (oculta notas); CURATION_ONLY preserva tudo")
    void internalPreservesDiagnostics_curationOnlyPreservesEverything() {
        SourceEvidence rich = sourceEvidence(
                AuthorityLevel.LEGAL, SourceRole.PRIMARY, SourceQuality.STRONG,
                SourceDiversity.MATERIAL_DIVERSITY, FreshnessStatus.UNCERTAIN, "civa");
        DocumentedTaxiaAnswer answer = documented(
                AnswerType.CONSULTA_DOCUMENTADA, ParecerRequirement.NONE,
                VisibilityLevel.INTERNAL, List.of(rich));

        SourceEvidence internal = projectionService.project(answer, VisibilityLevel.INTERNAL)
                .visibleSources().get(0);
        assertThat(internal.sourceCore()).isEqualTo("civa");
        assertThat(internal.sourceDiversity()).isEqualTo(SourceDiversity.MATERIAL_DIVERSITY);
        assertThat(internal.usedInAnswer()).isTrue();
        assertThat(internal.derivativeOrReplicated()).isFalse();
        assertThat(internal.notesInternal()).isEmpty(); // reservado a CURATION_ONLY

        SourceEvidence curation = projectionService.project(answer, VisibilityLevel.CURATION_ONLY)
                .visibleSources().get(0);
        assertThat(curation.notesInternal()).containsExactly("Nota interna sensível.");
        assertThat(curation.excerpt()).isEqualTo("Excerto sensível.");
        assertThat(curation.sourceCore()).isEqualTo("civa");
    }

    // ── Cenário 10 / 14 — Projecção não recalcula decisão ─────────────────────

    @Test
    @DisplayName("Cenário 10/14 — fontes fortes não fazem a projecção reclassificar a decisão")
    void projection_neverRecalculatesDecision_evenWithStrongSources() {
        // Resposta-limite / REQUIRED com fontes fortes: tentativa de "enganar" a projecção.
        SourceEvidence strong = sourceEvidence(
                AuthorityLevel.LEGAL, SourceRole.PRIMARY, SourceQuality.STRONG,
                SourceDiversity.MATERIAL_DIVERSITY, FreshnessStatus.CURRENT, "civa");
        DocumentedTaxiaAnswer answer = documented(
                AnswerType.RESPOSTA_LIMITE, ParecerRequirement.REQUIRED,
                VisibilityLevel.INTERNAL, List.of(strong));

        for (VisibilityLevel level : List.of(VisibilityLevel.EXTERNAL, VisibilityLevel.INTERNAL)) {
            AnswerProjection p = projectionService.project(answer, level);
            assertThat(p.visibleAnswerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
            assertThat(p.visibleParecerRequirement()).isEqualTo(ParecerRequirement.REQUIRED);
        }

        // Um PEDIDO_DE_PARECER também é preservado, nunca tratado como erro.
        DocumentedTaxiaAnswer parecer = documented(
                AnswerType.PEDIDO_DE_PARECER, ParecerRequirement.REQUIRED,
                VisibilityLevel.EXTERNAL, List.of(strong));
        AnswerProjection projected = projectionService.project(parecer, VisibilityLevel.EXTERNAL);
        assertThat(projected.visibleAnswerType()).isEqualTo(AnswerType.PEDIDO_DE_PARECER);
        assertThat(projected.visibleAnswer()).isNotBlank();
    }

    // ── Cenário 11 — riskLevel persistido não é aggregatedRiskLevel ───────────

    @Test
    @DisplayName("Cenário 11 — o mapper não inventa aggregatedRiskLevel a partir da entidade")
    void mapper_doesNotFabricateAggregatedRiskLevel() {
        var source = new AnswerSource("Código do IRC — Artigo 23.º", "CIRC, art. 23.º", 0.9);
        DocumentedTaxiaAnswer withSource = mapper.fromGroundedResponse(
                "Pergunta", grounded("Resposta.", AnswerSupportStatus.SUPPORTED, List.of(source), false));
        DocumentedTaxiaAnswer withoutSource = mapper.fromGroundedResponse(
                "Pergunta", grounded("Resposta.", AnswerSupportStatus.SUPPORTED, List.of(), false));

        // Sem cálculo de risco agregado real, permanece null (não se copia riskLevel da entidade).
        assertThat(withSource.aggregatedRiskLevel()).isNull();
        assertThat(withoutSource.aggregatedRiskLevel()).isNull();
    }

    // ── Cenário 12 — KnowledgeCurationStatus.OUTDATED ≠ FreshnessStatus.OUTDATED ─

    @Test
    @DisplayName("Cenário 12 — freshness só é OUTDATED com marcadores textuais, não por estado de curadoria")
    void freshness_isTextDriven_notCurationStatusDriven() {
        // Uma fonte legal comum, sem marcadores de revogação/caducidade, NÃO é OUTDATED
        // (freshness é actualidade textual da fonte, nunca o KnowledgeCurationStatus.OUTDATED).
        var plain = new AnswerSource("Código do IVA — Artigo 18.º", "CIVA, art. 18.º", 0.9);
        assertThat(sourceAssessmentService.assessFreshnessStatus(plain))
                .isEqualTo(FreshnessStatus.UNCERTAIN);

        // Só marcadores explícitos de revogação/caducidade produzem OUTDATED.
        var revoked = new AnswerSource("Ofício revogado", "sem efeito", 0.5);
        assertThat(sourceAssessmentService.assessFreshnessStatus(revoked))
                .isEqualTo(FreshnessStatus.OUTDATED);
    }
}
