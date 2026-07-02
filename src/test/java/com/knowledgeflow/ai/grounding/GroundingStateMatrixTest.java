package com.knowledgeflow.ai.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.AIService;
import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cobertura dos 5 estados de grounding com fixtures controladas.
 * Zero chamadas externas — AIService é sempre mocked.
 */
@ExtendWith(MockitoExtension.class)
class GroundingStateMatrixTest {

    private static final GroundingProperties PROPS_REJECT =
            new GroundingProperties(true, 1, 1, 0.0, true, true);
    private static final GroundingProperties PROPS_PERMISSIVE =
            new GroundingProperties(true, 1, 1, 0.0, false, true);

    @Mock private AIService aiService;

    private GroundingService serviceReject;
    private GroundingService servicePermissive;

    @BeforeEach
    void setUp() {
        var evaluator   = new ContextSufficiencyEvaluator(PROPS_REJECT);
        var validator   = new AnswerGroundingValidator(PROPS_REJECT);
        var factory     = new SafeResponseFactory();
        serviceReject   = new GroundingService(evaluator, validator, factory, aiService, PROPS_REJECT);

        var evaluatorP  = new ContextSufficiencyEvaluator(PROPS_PERMISSIVE);
        var validatorP  = new AnswerGroundingValidator(PROPS_PERMISSIVE);
        servicePermissive = new GroundingService(evaluatorP, validatorP, factory, aiService, PROPS_PERMISSIVE);
    }

    // ── SUPPORTED ──────────────────────────────────────────────────────────────

    @Test
    void state_SUPPORTED_providerCalledTrue_responseNotRejected() {
        var context = List.of(new RetrievedCase(
                "IVA Regime Geral",
                "Qual a taxa normal de IVA?",
                "A taxa normal de IVA aplicável é 23%.",
                0.9));
        when(aiService.complete(any())).thenReturn(stub("A taxa normal de IVA é 23%."));

        var result = serviceReject.process("Qual a taxa normal de IVA?", null, context);

        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.SUPPORTED);
        assertThat(result.providerCalled()).isTrue();
        assertThat(result.responseRejected()).isFalse();
        assertThat(result.unsupportedClaimsCount()).isZero();
        assertThat(result.sources()).isNotEmpty();
    }

    @Test
    void state_SUPPORTED_claimInContext_isNotUnsupported() {
        var context = List.of(new RetrievedCase(
                "IVA",
                "Taxa normal?",
                "A taxa normal de IVA aplicável é 23%.",
                0.9));
        when(aiService.complete(any())).thenReturn(stub("A taxa normal de IVA é 23%."));

        var result = serviceReject.process("Qual a taxa normal de IVA?", null, context);

        assertThat(result.unsupportedClaimsCount()).isZero();
        assertThat(result.requiresHumanValidation()).isFalse();
    }

    // ── PARTIALLY_SUPPORTED ────────────────────────────────────────────────────

    @Test
    void state_PARTIALLY_SUPPORTED_someSupportedSomeNot_withPermissiveProps() {
        var context = List.of(new RetrievedCase(
                "IVA",
                "Taxa normal?",
                "A taxa normal de IVA aplicável é 23%.",
                0.9));
        // Stub response: 23% (in context = supported) and 90 dias (NOT in context = unsupported)
        when(aiService.complete(any())).thenReturn(
                stub("A taxa é 23%. O prazo é de 90 dias."));

        var result = servicePermissive.process("Qual a taxa e o prazo?", null, context);

        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.PARTIALLY_SUPPORTED);
        assertThat(result.providerCalled()).isTrue();
        assertThat(result.responseRejected()).isFalse();
        assertThat(result.unsupportedClaimsCount()).isGreaterThan(0);
        assertThat(result.requiresHumanValidation()).isTrue();
    }

    @Test
    void state_PARTIALLY_SUPPORTED_missingInformationNotEmpty() {
        var context = List.of(new RetrievedCase(
                "IVA",
                "Taxa normal?",
                "A taxa normal de IVA é 23%.",
                0.9));
        when(aiService.complete(any())).thenReturn(
                stub("A taxa é 23%. O prazo é de 90 dias."));

        var result = servicePermissive.process("Qual a taxa e o prazo?", null, context);

        // PARTIALLY_SUPPORTED does not expose the unsupported claim as validated
        assertThat(result.responseRejected()).isFalse();
        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.PARTIALLY_SUPPORTED);
    }

    // ── INSUFFICIENT_CONTEXT ──────────────────────────────────────────────────

    @Test
    void state_INSUFFICIENT_CONTEXT_emptyContextList_providerNotCalled() {
        var result = serviceReject.process("Qual a taxa normal de IVA?", null, List.of());

        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
        assertThat(result.providerCalled()).isFalse();
        assertThat(result.provider()).isEqualTo("none");
        assertThat(result.model()).isEqualTo("none");
        assertThat(result.inputTokens()).isZero();
        assertThat(result.outputTokens()).isZero();
    }

    @Test
    void state_INSUFFICIENT_CONTEXT_answerIsNonBlankSafeRefusal() {
        var result = serviceReject.process("Qual a taxa normal de IVA?", null, List.of());

        assertThat(result.answer()).isNotBlank();
        assertThat(result.responseRejected()).isFalse();
    }

    // ── REQUIRES_HUMAN_REVIEW ─────────────────────────────────────────────────

    @Test
    void state_REQUIRES_HUMAN_REVIEW_highRiskQuestion_withContext_requiresHumanValidation() {
        var context = List.of(new RetrievedCase(
                "Notificação AT",
                "Procedimento AT?",
                "Perante notificação da AT, o contribuinte deve responder no prazo indicado.",
                0.9));
        when(aiService.complete(any())).thenReturn(
                stub("Esta situação requer avaliação por especialista."));

        var result = serviceReject.process(
                "A empresa recebeu uma notificação da Autoridade Tributária.", null, context);

        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.REQUIRES_HUMAN_REVIEW);
        assertThat(result.providerCalled()).isTrue();
        assertThat(result.requiresHumanValidation()).isTrue();
        assertThat(result.validationMessage()).isNotBlank();
    }

    @Test
    void state_REQUIRES_HUMAN_REVIEW_highRiskKeyword_emptyContext_humanReviewPreserved() {
        // Even without context, high-risk question propagates humanReviewRequired
        var result = serviceReject.process(
                "Qual o IMT na transmissão de um imóvel?", null, List.of());

        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
        assertThat(result.requiresHumanValidation()).isTrue();
        assertThat(result.providerCalled()).isFalse();
    }

    // ── REJECTED_UNSUPPORTED ──────────────────────────────────────────────────

    @Test
    void state_REJECTED_UNSUPPORTED_inventedRate_responseRejected() {
        var context = List.of(new RetrievedCase(
                "IVA",
                "Qual a taxa normal?",
                "A taxa aplicável é 23%.",
                0.9));
        // Stub invents 17% — not in context
        when(aiService.complete(any())).thenReturn(stub("A taxa aplicável é 17%."));

        var result = serviceReject.process("Qual a taxa?", null, context);

        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.REJECTED_UNSUPPORTED);
        assertThat(result.providerCalled()).isTrue();
        assertThat(result.responseRejected()).isTrue();
        assertThat(result.unsupportedClaimsCount()).isGreaterThan(0);
    }

    @Test
    void state_REJECTED_UNSUPPORTED_answerIsSafeMessageNotInventedClaim() {
        var context = List.of(new RetrievedCase(
                "IVA", "Taxa normal?", "A taxa aplicável é 23%.", 0.9));
        when(aiService.complete(any())).thenReturn(stub("A taxa aplicável é 17%."));

        var result = serviceReject.process("Qual a taxa?", null, context);

        // The safe rejection message must not expose the invented "17%" as validated
        assertThat(result.answer()).doesNotContain("17%");
        assertThat(result.responseRejected()).isTrue();
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private AIResponse stub(String content) {
        return new AIResponse("stub", "stub", content, 10, 5, 100L);
    }
}
