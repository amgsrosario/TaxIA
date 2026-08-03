package com.knowledgeflow.ai.documented;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ai.grounding.AnswerSource;
import com.knowledgeflow.ai.grounding.AnswerSupportStatus;
import com.knowledgeflow.ai.grounding.GroundedAIResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Testes do conversor mínimo e aditivo {@link DocumentedTaxiaAnswerMapper} (tarefa D4).
 *
 * <p>Valida apenas os defaults transitórios do mapeamento — não o algoritmo definitivo de
 * scoring/thresholds nem a projecção por visibilidade (fases D5–D7).
 */
class DocumentedTaxiaAnswerMapperTest {

    private final DocumentedTaxiaAnswerMapper mapper =
            new DocumentedTaxiaAnswerMapper(new SourceAssessmentService());

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
    void nullSupportStatus_defaultsToRespostaLimite_withoutThrowing() {
        DocumentedTaxiaAnswer answer = mapper.fromGroundedResponse(
                "Pergunta", grounded(null, List.of(), false));

        assertThat(answer.answerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(answer.parecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
    }
}
