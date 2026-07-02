package com.knowledgeflow.ai.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContextSufficiencyEvaluatorTest {

    private static final GroundingProperties PROPS =
            new GroundingProperties(true, 1, 1, 0.0, true, true);

    private ContextSufficiencyEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new ContextSufficiencyEvaluator(PROPS);
    }

    @Test
    void emptyList_returnsInsufficientContext() {
        var result = evaluator.evaluate(List.of(), "Qual a taxa de IVA?");

        assertThat(result.status()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
        assertThat(result.fragmentCount()).isZero();
        assertThat(result.distinctSourceCount()).isZero();
        assertThat(result.isSufficient()).isFalse();
    }

    @Test
    void listWithNullContent_returnsInsufficientContext() {
        var cases = List.of(new RetrievedCase("Título A", "Pergunta A", null, 0.9));

        var result = evaluator.evaluate(cases, "Pergunta");

        assertThat(result.status()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
        assertThat(result.reason()).contains("conteúdo validado");
    }

    @Test
    void listWithBlankContent_returnsInsufficientContext() {
        var cases = List.of(new RetrievedCase("Título A", "Pergunta A", "   ", 0.9));

        var result = evaluator.evaluate(cases, "Pergunta");

        assertThat(result.status()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
    }

    @Test
    void singleValidCase_returnsSupported() {
        var cases = List.of(new RetrievedCase("IVA — Taxa Normal", "Qual a taxa?", "A taxa normal de IVA é 23%.", 0.85));

        var result = evaluator.evaluate(cases, "Qual a taxa de IVA?");

        assertThat(result.status()).isEqualTo(AnswerSupportStatus.SUPPORTED);
        assertThat(result.fragmentCount()).isEqualTo(1);
        assertThat(result.distinctSourceCount()).isEqualTo(1);
        assertThat(result.bestRelevanceScore()).isEqualTo(0.85);
        assertThat(result.isSufficient()).isTrue();
    }

    @Test
    void multipleCasesFromSameSource_countedAsOneDistinctSource() {
        var cases = List.of(
                new RetrievedCase("Fonte A", "Q1", "Conteúdo 1", 0.8),
                new RetrievedCase("Fonte A", "Q2", "Conteúdo 2", 0.7));

        var result = evaluator.evaluate(cases, "Pergunta");

        assertThat(result.fragmentCount()).isEqualTo(2);
        assertThat(result.distinctSourceCount()).isEqualTo(1);
    }

    @Test
    void multipleCasesFromDifferentSources_countedCorrectly() {
        var cases = List.of(
                new RetrievedCase("Fonte A", "Q1", "Conteúdo 1", 0.8),
                new RetrievedCase("Fonte B", "Q2", "Conteúdo 2", 0.9));

        var result = evaluator.evaluate(cases, "Pergunta");

        assertThat(result.distinctSourceCount()).isEqualTo(2);
        assertThat(result.bestRelevanceScore()).isEqualTo(0.9);
    }

    @Test
    void questionWithHighRiskKeyword_imóvel_returnsRequiresHumanReview() {
        var cases = List.of(new RetrievedCase("IMT", "Questão imóvel", "Conteúdo relevante sobre imóvel.", 0.8));

        var result = evaluator.evaluate(cases, "Qual o IMT sobre a compra de um imóvel?");

        assertThat(result.status()).isEqualTo(AnswerSupportStatus.REQUIRES_HUMAN_REVIEW);
        assertThat(result.humanReviewRequired()).isTrue();
        assertThat(result.isSufficient()).isTrue();
    }

    @Test
    void questionWithHighRiskKeyword_contencioso_returnsRequiresHumanReview() {
        var cases = List.of(new RetrievedCase("Contencioso", "Questão", "Conteúdo.", 0.75));

        var result = evaluator.evaluate(cases, "Como iniciar um processo de contencioso fiscal?");

        assertThat(result.status()).isEqualTo(AnswerSupportStatus.REQUIRES_HUMAN_REVIEW);
        assertThat(result.humanReviewRequired()).isTrue();
    }

    @Test
    void minimumFragmentsNotMet_returnsInsufficientContext() {
        var strictProps = new GroundingProperties(true, 3, 1, 0.0, true, true);
        var strictEvaluator = new ContextSufficiencyEvaluator(strictProps);
        var cases = List.of(
                new RetrievedCase("Fonte A", "Q1", "Conteúdo 1", 0.8),
                new RetrievedCase("Fonte B", "Q2", "Conteúdo 2", 0.9));

        var result = strictEvaluator.evaluate(cases, "Pergunta");

        assertThat(result.status()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
        assertThat(result.reason()).contains("mínimo");
    }

    @Test
    void minimumDistinctSourcesNotMet_returnsInsufficientContext() {
        var strictProps = new GroundingProperties(true, 1, 2, 0.0, true, true);
        var strictEvaluator = new ContextSufficiencyEvaluator(strictProps);
        var cases = List.of(new RetrievedCase("Fonte A", "Q1", "Conteúdo 1", 0.8));

        var result = strictEvaluator.evaluate(cases, "Pergunta");

        assertThat(result.status()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
        assertThat(result.reason()).contains("Fontes distintas");
    }

    @Test
    void relevanceBelowMinimum_returnsInsufficientContext() {
        var strictProps = new GroundingProperties(true, 1, 1, 0.8, true, true);
        var strictEvaluator = new ContextSufficiencyEvaluator(strictProps);
        var cases = List.of(new RetrievedCase("Fonte A", "Q1", "Conteúdo 1", 0.5));

        var result = strictEvaluator.evaluate(cases, "Pergunta");

        assertThat(result.status()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
        assertThat(result.reason()).contains("Relevância");
    }

    @Test
    void supportedAssessment_isSufficientTrue() {
        var cases = List.of(new RetrievedCase("F", "Q", "C", 0.9));
        var result = evaluator.evaluate(cases, "Pergunta simples");

        assertThat(result.isSufficient()).isTrue();
    }

    @Test
    void insufficientAssessment_isSufficientFalse() {
        var result = evaluator.evaluate(List.of(), "Pergunta");

        assertThat(result.isSufficient()).isFalse();
    }

    // --- Human review propagated even when context is insufficient ---

    @Test
    void emptyContext_withHighRiskKeyword_humanReviewRequiredIsTrue() {
        var result = evaluator.evaluate(List.of(),
                "A empresa recebeu uma notificação da Autoridade Tributária.");

        assertThat(result.status()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
        assertThat(result.humanReviewRequired()).isTrue();
    }

    @Test
    void emptyContext_withHighRiskKeyword_statusRemainsInsufficientContext() {
        // humanReviewRequired=true must NOT upgrade status to REQUIRES_HUMAN_REVIEW
        // when the context is actually insufficient.
        var result = evaluator.evaluate(List.of(),
                "Qual o IMT na transmissão de imóvel?");

        assertThat(result.status()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
    }

    @Test
    void emptyContext_withoutHighRiskKeyword_humanReviewRequiredIsFalse() {
        var result = evaluator.evaluate(List.of(), "Qual a taxa normal de IVA?");

        assertThat(result.humanReviewRequired()).isFalse();
    }

    @Test
    void insufficientFragments_withHighRiskKeyword_humanReviewRequiredIsTrue() {
        // Context has a case but fragments below minimum (using min=2)
        var strictProps = new GroundingProperties(true, 2, 1, 0.0, true, true);
        var strictEvaluator = new ContextSufficiencyEvaluator(strictProps);
        var cases = List.of(new RetrievedCase("AT", "Q", "Conteúdo.", 0.9));

        var result = strictEvaluator.evaluate(cases,
                "A empresa recebeu uma notificação da Autoridade Tributária.");

        assertThat(result.status()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
        assertThat(result.humanReviewRequired()).isTrue();
    }

    @Test
    void sufficientContext_withHighRiskKeyword_statusIsRequiresHumanReview() {
        var cases = List.of(new RetrievedCase("AT", "Q", "Conteúdo relevante.", 0.9));
        var result = evaluator.evaluate(cases,
                "A empresa recebeu uma notificação da Autoridade Tributária.");

        assertThat(result.status()).isEqualTo(AnswerSupportStatus.REQUIRES_HUMAN_REVIEW);
        assertThat(result.humanReviewRequired()).isTrue();
    }
}
