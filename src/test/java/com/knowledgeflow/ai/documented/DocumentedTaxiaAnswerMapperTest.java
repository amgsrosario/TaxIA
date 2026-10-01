package com.knowledgeflow.ai.documented;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ai.grounding.AnswerSource;
import com.knowledgeflow.ai.grounding.AnswerSupportStatus;
import com.knowledgeflow.ai.grounding.GroundedAIResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Testes do conversor mínimo e aditivo {@link DocumentedTaxiaAnswerMapper} (tarefa D4).
 *
 * <p>Valida apenas os defaults transitórios do mapeamento — não o algoritmo definitivo de
 * scoring/thresholds nem a projecção por visibilidade (fases D5–D7).
 */
class DocumentedTaxiaAnswerMapperTest {

    private final DocumentedTaxiaAnswerMapper mapper =
            new DocumentedTaxiaAnswerMapper(new SourceAssessmentService(), new AnswerDecisionService(),
                    new InternalDiagnosticsBuilder());

    private GroundedAIResponse grounded(AnswerSupportStatus status, List<AnswerSource> sources,
            boolean requiresHumanValidation) {
        return new GroundedAIResponse(
                "Resposta.", status, "Motivo", sources,
                List.of(), List.of(), requiresHumanValidation, null,
                true, false, null, 0, "stub", "stub", 0, 0, 0L);
    }

    @Test
    void supported_mapsToConsultaDocumentada_andParecerNone() {
        DocumentedTaxiaAnswer answer =
                mapper.fromGroundedResponse("Qual a taxa de IVA?", grounded(AnswerSupportStatus.SUPPORTED, List.of(), false));

        assertThat(answer.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA);
        assertThat(answer.parecerRequirement()).isEqualTo(ParecerRequirement.NONE);
        assertThat(answer.supportStatus()).isEqualTo(AnswerSupportStatus.SUPPORTED);
        assertThat(answer.question()).isEqualTo("Qual a taxa de IVA?");
    }

    @Test
    void requiresHumanReview_parecerNotNone_andIsNotAnError() {
        DocumentedTaxiaAnswer answer = mapper.fromGroundedResponse(
                "Pergunta sensível.", grounded(AnswerSupportStatus.REQUIRES_HUMAN_REVIEW, List.of(), true));

        // Não é erro: continua a ser uma resposta documentada (com limitações), não uma falha.
        assertThat(answer.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(answer.parecerRequirement()).isNotEqualTo(ParecerRequirement.NONE);
        assertThat(answer.technicalAnswer()).isNotBlank();
    }

    @Test
    void sources_mappedToSourceEvidence_usingSourceAssessmentService() {
        // Referência legal (CIVA) → o serviço avalia LEGAL / STRONG e usa a referência como núcleo.
        var src = new AnswerSource("IVA — Regime Geral", "CIVA art. 18.º", 0.9);
        DocumentedTaxiaAnswer answer = mapper.fromGroundedResponse(
                "Pergunta", grounded(AnswerSupportStatus.SUPPORTED, List.of(src), false));

        assertThat(answer.sources()).hasSize(1);
        SourceEvidence evidence = answer.sources().get(0);
        assertThat(evidence.title()).isEqualTo("IVA — Regime Geral");
        assertThat(evidence.legalReference()).isEqualTo("CIVA art. 18.º");
        // Campos vindos do SourceAssessmentService (D5), já não são defaults cegos.
        assertThat(evidence.authorityLevel()).isEqualTo(AuthorityLevel.LEGAL);
        assertThat(evidence.sourceQuality()).isEqualTo(SourceQuality.STRONG);
        assertThat(evidence.sourceRole()).isEqualTo(SourceRole.PRIMARY);
        assertThat(evidence.sourceCore()).isEqualTo("civa art. 18.º");
        assertThat(evidence.sourceDiversityGroup()).isEqualTo("civa art. 18.º");
        assertThat(evidence.freshnessStatus()).isEqualTo(FreshnessStatus.UNCERTAIN);
        assertThat(evidence.usedInAnswer()).isTrue();
        assertThat(evidence.supportsConclusion()).isTrue();
        assertThat(evidence.derivativeOrReplicated()).isFalse();
    }

    @Test
    void sources_defaultsStaySafe_forInternalCuratedWithoutReference() {
        var src = new AnswerSource("Nota interna sobre enquadramento", "", 0.5);
        DocumentedTaxiaAnswer answer = mapper.fromGroundedResponse(
                "Pergunta", grounded(AnswerSupportStatus.PARTIALLY_SUPPORTED, List.of(src), false));

        SourceEvidence evidence = answer.sources().get(0);
        assertThat(evidence.authorityLevel()).isEqualTo(AuthorityLevel.INTERNAL_CURATED);
        assertThat(evidence.sourceQuality()).isEqualTo(SourceQuality.LIMITED);
        assertThat(evidence.freshnessStatus()).isEqualTo(FreshnessStatus.UNCERTAIN);
        assertThat(evidence.sourceDiversity()).isEqualTo(SourceDiversity.MIXED_OR_UNCLEAR);
    }

    @Test
    void freshnessStatus_defaultsToUncertain() {
        DocumentedTaxiaAnswer answer = mapper.fromGroundedResponse(
                "Pergunta", grounded(AnswerSupportStatus.SUPPORTED, List.of(), false));

        assertThat(answer.freshnessStatus()).isEqualTo(FreshnessStatus.UNCERTAIN);
    }

    @Test
    void noSources_doesNotBreak_producesEmptyList() {
        DocumentedTaxiaAnswer answer = mapper.fromGroundedResponse(
                "Pergunta", grounded(AnswerSupportStatus.INSUFFICIENT_CONTEXT, List.of(), false));

        assertThat(answer.sources()).isEmpty();
        assertThat(answer.answerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
    }

    @Test
    void nullSources_doesNotBreak_producesEmptyList() {
        DocumentedTaxiaAnswer answer = mapper.fromGroundedResponse(
                "Pergunta", grounded(AnswerSupportStatus.INSUFFICIENT_CONTEXT, null, false));

        assertThat(answer.sources()).isEmpty();
    }

    @Test
    void rejectedUnsupported_parecerRequired_andRespostaLimite_notError() {
        DocumentedTaxiaAnswer answer = mapper.fromGroundedResponse(
                "Pergunta", grounded(AnswerSupportStatus.REJECTED_UNSUPPORTED, List.of(), true));

        assertThat(answer.answerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(answer.parecerRequirement()).isEqualTo(ParecerRequirement.REQUIRED);
        // Resposta-limite não é "não resposta": mantém texto e diagnóstico.
        assertThat(answer.technicalAnswer()).isNotBlank();
    }

    @Test
    void internalDiagnostics_arePopulated_observeDecision_andDoNotFabricateRisk() {
        var src = new AnswerSource("Código do IVA — Artigo 18.º", "CIVA art. 18.º", 0.9);
        DocumentedTaxiaAnswer answer = mapper.fromGroundedResponse(
                "Pergunta", grounded(AnswerSupportStatus.SUPPORTED, List.of(src), false));

        InternalDiagnostics diag = answer.internalDiagnostics();
        assertThat(diag).isNotNull();
        // Observa a decisão real (lê, não recalcula).
        assertThat(diag.decisionSignals()).contains("answerType=CONSULTA_DOCUMENTADA");
        assertThat(diag.sourceSignals()).contains("fontesUsadas=1", "fonte oficial/legal presente");
        // Risco agregado não é fabricado a partir da entidade.
        assertThat(answer.aggregatedRiskLevel()).isNull();
        assertThat(diag.riskSignals())
                .anyMatch(s -> s.contains("aggregatedRiskLevel ausente"));
        // Política de projecção declarada; sem verificação online.
        assertThat(diag.projectionSignals())
                .anyMatch(s -> s.contains("EXTERNAL/DEMO ocultam"));
        assertThat(diag.hiddenForExternal()).contains("internalDiagnostics", "notesInternal");
    }

    @Test
    void nullSupportStatus_defaultsToRespostaLimite_withoutThrowing() {
        DocumentedTaxiaAnswer answer = mapper.fromGroundedResponse(
                "Pergunta", grounded(null, List.of(), false));

        assertThat(answer.answerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(answer.parecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
    }

    // --- M3: fontes documentais curadas ---

    private static final UUID QA_ID = UUID.fromString("00000000-0000-0000-0000-00000000a930");

    private static AnswerSource qaSource() {
        return new AnswerSource("Que despesas podem ser deduzidas aos rendimentos prediais?",
                "Que despesas podem ser deduzidas aos rendimentos prediais?", 0.9, QA_ID);
    }

    private static List<ResolvedAnswerSource> officialSources(AnswerSource origin) {
        return List.of(
                ResolvedAnswerSource.curated(origin, new ResolvedAnswerSource.CuratedSource(
                        UUID.fromString("00000000-0000-0000-0000-000000000041"), "LEGISLATION",
                        "Código do IRS — Artigo 41.º — Deduções aos rendimentos prediais", "CIRS, art. 41.º",
                        "https://info.portaldasfinancas.gov.pt/pt/informacao_fiscal/codigos_tributarios/cirs_rep/Pages/irs41.aspx")),
                ResolvedAnswerSource.curated(origin, new ResolvedAnswerSource.CuratedSource(
                        UUID.fromString("00000000-0000-0000-0000-000000005930"), "OFFICIAL_FAQ",
                        "Portal das Finanças — FAQ 5930 — Rendimentos prediais: despesas dedutíveis", "CIRS, art. 41.º",
                        "https://info.portaldasfinancas.gov.pt/pt/apoio_contribuinte/questoes_frequentes/pages/faqs-00358.aspx")));
    }

    @Test
    void curatedSources_mapToRealDocumentaryEvidence() {
        DocumentedTaxiaAnswer answer = mapper.fromGroundedResponse("Despesas dedutíveis?",
                grounded(AnswerSupportStatus.SUPPORTED, List.of(qaSource()), false), officialSources(qaSource()));

        assertThat(answer.sources()).hasSize(2);
        SourceEvidence law = answer.sources().get(0);
        assertThat(law.title()).isEqualTo("Código do IRS — Artigo 41.º — Deduções aos rendimentos prediais");
        assertThat(law.sourceType()).isEqualTo("LEGISLATION");
        assertThat(law.legalReference()).isEqualTo("CIRS, art. 41.º");
        assertThat(law.url()).endsWith("/irs41.aspx");
        assertThat(law.sourceId()).isEqualTo("00000000-0000-0000-0000-000000000041");
        assertThat(law.authorityLevel()).isEqualTo(AuthorityLevel.LEGAL);
        assertThat(law.sourceQuality()).isEqualTo(SourceQuality.STRONG);
        // Política de actualidade inalterada: sem marcadores de revogação → não confirmada.
        assertThat(law.freshnessStatus()).isEqualTo(FreshnessStatus.UNCERTAIN);
        // Q&A de origem só como rastreabilidade interna.
        assertThat(law.relatedSources()).containsExactly("knowledge-qa:" + QA_ID);

        SourceEvidence faq = answer.sources().get(1);
        assertThat(faq.sourceType()).isEqualTo("OFFICIAL_FAQ");
        assertThat(faq.authorityLevel()).isEqualTo(AuthorityLevel.OFFICIAL_FAQ);
        assertThat(faq.url()).endsWith("/faqs-00358.aspx");

        // O título da Q&A deixa de ser apresentado como fonte.
        assertThat(answer.sources()).extracting(SourceEvidence::title)
                .doesNotContain("Que despesas podem ser deduzidas aos rendimentos prediais?");
    }

    @Test
    void fallbackEntries_mapExactlyLikeTheLegacyMapping() {
        AnswerSource legacy = new AnswerSource("IVA — Regime Geral", "CIVA art. 18.º", 0.9);
        GroundedAIResponse g = grounded(AnswerSupportStatus.SUPPORTED, List.of(legacy), false);

        DocumentedTaxiaAnswer viaLegacy = mapper.fromGroundedResponse("P?", g);
        DocumentedTaxiaAnswer viaFallback =
                mapper.fromGroundedResponse("P?", g, List.of(ResolvedAnswerSource.fallback(legacy)));

        assertThat(viaFallback.sources()).isEqualTo(viaLegacy.sources());
        assertThat(viaFallback.answerType()).isEqualTo(viaLegacy.answerType());
        assertThat(viaFallback.parecerRequirement()).isEqualTo(viaLegacy.parecerRequirement());
    }

    @Test
    void officialCuratedSources_doNotDegradeAnswerTypeOrParecer_comparedWithQaTitleSource() {
        // Regressão M3: a mesma resposta SUPPORTED, antes com a Q&A como fonte e agora com a
        // legislação + FAQ oficial curadas, mantém a forma e o encaminhamento.
        GroundedAIResponse g = grounded(AnswerSupportStatus.SUPPORTED, List.of(qaSource()), false);

        DocumentedTaxiaAnswer before = mapper.fromGroundedResponse("Despesas dedutíveis?", g);
        DocumentedTaxiaAnswer after = mapper.fromGroundedResponse("Despesas dedutíveis?", g, officialSources(qaSource()));

        assertThat(before.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA);
        assertThat(before.parecerRequirement()).isEqualTo(ParecerRequirement.NONE);
        assertThat(after.answerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA);
        assertThat(after.parecerRequirement()).isEqualTo(ParecerRequirement.NONE);
        assertThat(after.warnings()).isEqualTo(before.warnings());
    }
}
