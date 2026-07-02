package com.knowledgeflow.ai.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.knowledgeflow.ai.AIResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SafeResponseFactoryTest {

    private SafeResponseFactory factory;

    @BeforeEach
    void setUp() {
        factory = new SafeResponseFactory();
    }

    // --- buildRefusal ---

    @Test
    void buildRefusal_providerCalledIsFalse() {
        var assessment = insufficientAssessment("Sem contexto.");
        assertThat(factory.buildRefusal(assessment).providerCalled()).isFalse();
    }

    @Test
    void buildRefusal_answerIsNonEmpty() {
        var assessment = insufficientAssessment("Sem contexto.");
        assertThat(factory.buildRefusal(assessment).answer()).isNotBlank();
    }

    @Test
    void buildRefusal_sourceListIsEmpty() {
        var assessment = insufficientAssessment("Sem contexto.");
        assertThat(factory.buildRefusal(assessment).sources()).isEmpty();
    }

    @Test
    void buildRefusal_statusIsInsufficientContext() {
        var assessment = insufficientAssessment("Sem contexto.");
        assertThat(factory.buildRefusal(assessment).supportStatus())
                .isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
    }

    @Test
    void buildRefusal_reasonPropagatedFromAssessment() {
        var assessment = insufficientAssessment("Motivo específico do teste.");
        assertThat(factory.buildRefusal(assessment).supportReason())
                .isEqualTo("Motivo específico do teste.");
    }

    @Test
    void buildRefusal_responseRejectedIsFalse() {
        var assessment = insufficientAssessment("Sem contexto.");
        assertThat(factory.buildRefusal(assessment).responseRejected()).isFalse();
    }

    @Test
    void buildRefusal_tokensAreZero() {
        var assessment = insufficientAssessment("Sem contexto.");
        var r = factory.buildRefusal(assessment);
        assertThat(r.inputTokens()).isZero();
        assertThat(r.outputTokens()).isZero();
        assertThat(r.durationMillis()).isZero();
    }

    @Test
    void buildRefusal_missingElementsPropagatedFromAssessment() {
        var assessment = new ContextSufficiencyAssessment(
                AnswerSupportStatus.INSUFFICIENT_CONTEXT,
                "Sem contexto.",
                List.of("elemento A", "elemento B"),
                0, 0, 0.0, false);
        assertThat(factory.buildRefusal(assessment).missingInformation())
                .containsExactly("elemento A", "elemento B");
    }

    // --- buildRejected ---

    @Test
    void buildRejected_providerCalledIsTrue() {
        var ai = stubAIResponse();
        var assessment = sufficientAssessment();
        var validation = rejectedValidation("1 afirmação sem suporte", 1);

        assertThat(factory.buildRejected(ai, assessment, validation).providerCalled()).isTrue();
    }

    @Test
    void buildRejected_responseRejectedIsTrue() {
        var ai = stubAIResponse();
        assertThat(factory.buildRejected(ai, sufficientAssessment(), rejectedValidation("motivo", 1))
                .responseRejected()).isTrue();
    }

    @Test
    void buildRejected_statusIsRejectedUnsupported() {
        var ai = stubAIResponse();
        assertThat(factory.buildRejected(ai, sufficientAssessment(), rejectedValidation("motivo", 1))
                .supportStatus()).isEqualTo(AnswerSupportStatus.REJECTED_UNSUPPORTED);
    }

    @Test
    void buildRejected_preservesProviderAndModel() {
        var ai = new AIResponse("anthropic", "claude-haiku-4-5-20251001", "conteúdo", 100, 50, 1200L);
        var result = factory.buildRejected(ai, sufficientAssessment(), rejectedValidation("motivo", 1));

        assertThat(result.provider()).isEqualTo("anthropic");
        assertThat(result.model()).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(result.inputTokens()).isEqualTo(100);
        assertThat(result.outputTokens()).isEqualTo(50);
        assertThat(result.durationMillis()).isEqualTo(1200L);
    }

    @Test
    void buildRejected_unsupportedClaimsCountReflectsValidation() {
        var ai = stubAIResponse();
        var validation = rejectedValidation("motivo", 3);
        assertThat(factory.buildRejected(ai, sufficientAssessment(), validation).unsupportedClaimsCount())
                .isEqualTo(3);
    }

    @Test
    void buildRejected_answerIsNonEmpty() {
        var ai = stubAIResponse();
        assertThat(factory.buildRejected(ai, sufficientAssessment(), rejectedValidation("motivo", 1))
                .answer()).isNotBlank();
    }

    @Test
    void buildRejected_requiresHumanValidationIsTrue() {
        var ai = stubAIResponse();
        assertThat(factory.buildRejected(ai, sufficientAssessment(), rejectedValidation("motivo", 1))
                .requiresHumanValidation()).isTrue();
    }

    // --- buildRefusal: human review propagation ---

    @Test
    void buildRefusal_withHighRiskAssessment_requiresHumanValidationIsTrue() {
        var assessment = new ContextSufficiencyAssessment(
                AnswerSupportStatus.INSUFFICIENT_CONTEXT, "Sem contexto.",
                List.of(), 0, 0, 0.0, true); // humanReviewRequired=true

        assertThat(factory.buildRefusal(assessment).requiresHumanValidation()).isTrue();
    }

    @Test
    void buildRefusal_withHighRiskAssessment_validationMessageIsNotNull() {
        var assessment = new ContextSufficiencyAssessment(
                AnswerSupportStatus.INSUFFICIENT_CONTEXT, "Sem contexto.",
                List.of(), 0, 0, 0.0, true);

        assertThat(factory.buildRefusal(assessment).validationMessage()).isNotBlank();
    }

    @Test
    void buildRefusal_withoutHighRisk_requiresHumanValidationIsFalse() {
        var assessment = insufficientAssessment("Sem contexto.");
        assertThat(factory.buildRefusal(assessment).requiresHumanValidation()).isFalse();
    }

    @Test
    void buildRefusal_withoutHighRisk_validationMessageIsNull() {
        var assessment = insufficientAssessment("Sem contexto.");
        assertThat(factory.buildRefusal(assessment).validationMessage()).isNull();
    }

    // --- helpers ---

    private ContextSufficiencyAssessment insufficientAssessment(String reason) {
        return new ContextSufficiencyAssessment(
                AnswerSupportStatus.INSUFFICIENT_CONTEXT, reason, List.of(), 0, 0, 0.0, false);
    }

    private ContextSufficiencyAssessment sufficientAssessment() {
        return new ContextSufficiencyAssessment(
                AnswerSupportStatus.SUPPORTED, "Contexto OK.", List.of(), 1, 1, 0.8, false);
    }

    private AIResponse stubAIResponse() {
        return new AIResponse("stub", "stub", "Resposta do stub.", 0, 0, 0L);
    }

    private GroundingValidationResult rejectedValidation(String reason, int unsupportedCount) {
        List<SensitiveClaim> unsupported = java.util.stream.IntStream.range(0, unsupportedCount)
                .mapToObj(i -> new SensitiveClaim("claim-" + i, SensitiveClaimType.TAX_RATE, false, List.of()))
                .toList();
        return GroundingValidationResult.rejected(unsupported, unsupported, reason);
    }
}
