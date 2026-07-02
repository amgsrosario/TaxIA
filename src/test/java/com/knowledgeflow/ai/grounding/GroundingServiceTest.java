package com.knowledgeflow.ai.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.AIService;
import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroundingServiceTest {

    private static final GroundingProperties PROPS =
            new GroundingProperties(true, 1, 1, 0.0, true, true);
    private static final GroundingProperties PROPS_GROUNDING_DISABLED =
            new GroundingProperties(false, 1, 1, 0.0, false, false);
    private static final GroundingProperties PROPS_SKIP_DISABLED =
            new GroundingProperties(true, 1, 1, 0.0, true, false);

    @Mock
    private AIService aiService;

    private GroundingService service;
    private GroundingService serviceSkipDisabled;
    private GroundingService serviceGroundingDisabled;

    @BeforeEach
    void setUp() {
        var evaluator = new ContextSufficiencyEvaluator(PROPS);
        var validator = new AnswerGroundingValidator(PROPS);
        var safeFactory = new SafeResponseFactory();

        service = new GroundingService(evaluator, validator, safeFactory, aiService, PROPS);

        var evaluatorSkip = new ContextSufficiencyEvaluator(PROPS_SKIP_DISABLED);
        var validatorSkip = new AnswerGroundingValidator(PROPS_SKIP_DISABLED);
        serviceSkipDisabled = new GroundingService(evaluatorSkip, validatorSkip, safeFactory, aiService, PROPS_SKIP_DISABLED);

        var evaluatorOff = new ContextSufficiencyEvaluator(PROPS_GROUNDING_DISABLED);
        var validatorOff = new AnswerGroundingValidator(PROPS_GROUNDING_DISABLED);
        serviceGroundingDisabled = new GroundingService(evaluatorOff, validatorOff, safeFactory, aiService, PROPS_GROUNDING_DISABLED);
    }

    // --- Context insufficient: provider not called ---

    @Test
    void insufficientContext_emptyList_providerNeverCalled() {
        service.process("Qual a taxa?", null, List.of());

        verify(aiService, never()).complete(any());
    }

    @Test
    void insufficientContext_emptyList_returnsRefusalAnswer() {
        var result = service.process("Qual a taxa?", null, List.of());

        assertThat(result.providerCalled()).isFalse();
        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
        assertThat(result.answer()).isNotBlank();
    }

    @Test
    void insufficientContext_providerIsNone() {
        var result = service.process("Qual a taxa?", null, List.of());

        assertThat(result.provider()).isEqualTo("none");
        assertThat(result.model()).isEqualTo("none");
    }

    @Test
    void insufficientContext_skipDisabled_providerStillCalled() {
        when(aiService.complete(any())).thenReturn(
                new AIResponse("stub", "stub", "Resposta do stub.", 0, 0, 0L));

        serviceSkipDisabled.process("Qual a taxa?", null, List.of());

        verify(aiService, times(1)).complete(any());
    }

    // --- Context sufficient: provider called exactly once ---

    @Test
    void sufficientContext_providerCalledExactlyOnce() {
        var cases = validCases();
        when(aiService.complete(any())).thenReturn(stubResponse("Resposta válida sem afirmações sensíveis."));

        service.process("Qual o regime de IVA?", null, cases);

        verify(aiService, times(1)).complete(any());
    }

    @Test
    void sufficientContext_sourcesPopulatedFromRetrievedCases() {
        var cases = validCases();
        when(aiService.complete(any())).thenReturn(stubResponse("Resposta."));

        var result = service.process("Pergunta?", null, cases);

        assertThat(result.sources()).isNotEmpty();
        assertThat(result.sources().get(0).title()).isEqualTo("IVA — Regime Geral");
        assertThat(result.providerCalled()).isTrue();
    }

    @Test
    void sufficientContext_cleanAnswer_statusIsSupported() {
        var cases = validCases();
        when(aiService.complete(any())).thenReturn(stubResponse("Resposta sem afirmações sensíveis."));

        var result = service.process("Pergunta?", null, cases);

        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.SUPPORTED);
        assertThat(result.responseRejected()).isFalse();
    }

    // --- Controlled prompt includes grounding constraints ---

    @Test
    void controlledPromptContainsGroundingConstraints() {
        var cases = validCases();
        when(aiService.complete(any())).thenReturn(stubResponse("Resposta."));

        service.process("Pergunta?", null, cases);

        verify(aiService).complete(argThat(req ->
                req.systemPrompt() != null
                && req.systemPrompt().contains("INSTRUÇÕES OBRIGATÓRIAS DO SISTEMA TaxIA")));
    }

    @Test
    void controlledPromptIncludesUserSystemPrompt() {
        var cases = validCases();
        when(aiService.complete(any())).thenReturn(stubResponse("Resposta."));
        String userPrompt = "Responde sempre em português.";

        service.process("Pergunta?", userPrompt, cases);

        verify(aiService).complete(argThat(req ->
                req.systemPrompt() != null
                && req.systemPrompt().contains(userPrompt)));
    }

    @Test
    void controlledPromptIncludesCaseContent() {
        var cases = validCases();
        when(aiService.complete(any())).thenReturn(stubResponse("Resposta."));

        service.process("Pergunta?", null, cases);

        verify(aiService).complete(argThat(req ->
                req.systemPrompt() != null
                && req.systemPrompt().contains("IVA — Regime Geral")));
    }

    // --- Response rejected by validator ---

    @Test
    void responseWithInventedRate_rejectedAndRejectedAnswerReturned() {
        // Context has no tax rate; answer invents 23%
        var cases = List.of(new RetrievedCase("Sem taxa", "Pergunta geral", "Informação geral sem taxa.", 0.8));
        when(aiService.complete(any())).thenReturn(stubResponse("A taxa aplicável é de 23%."));

        var result = service.process("Qual a taxa?", null, cases);

        assertThat(result.responseRejected()).isTrue();
        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.REJECTED_UNSUPPORTED);
        assertThat(result.requiresHumanValidation()).isTrue();
        assertThat(result.providerCalled()).isTrue();
    }

    // --- Human review required ---

    @Test
    void highRiskQuestion_imóvel_requiresHumanValidation() {
        var cases = List.of(new RetrievedCase("IMT", "Questão", "Conteúdo relevante sobre imóvel.", 0.8));
        when(aiService.complete(any())).thenReturn(stubResponse("Resposta sem afirmações sensíveis."));

        var result = service.process("Qual o IMT sobre a compra de um imóvel?", null, cases);

        assertThat(result.requiresHumanValidation()).isTrue();
        assertThat(result.validationMessage()).isNotBlank();
        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.REQUIRES_HUMAN_REVIEW);
    }

    // --- Grounding disabled ---

    @Test
    void groundingDisabled_providerCalledEvenWithEmptyContext() {
        when(aiService.complete(any())).thenReturn(stubResponse("Resposta stub."));

        serviceGroundingDisabled.process("Pergunta?", null, List.of());

        verify(aiService, times(1)).complete(any());
    }

    // --- durationMillis propagated ---

    @Test
    void providerDurationMillisPropagatedToResponse() {
        var cases = validCases();
        when(aiService.complete(any())).thenReturn(
                new AIResponse("stub", "stub", "Resposta.", 10, 5, 350L));

        var result = service.process("Pergunta?", null, cases);

        assertThat(result.durationMillis()).isEqualTo(350L);
    }

    // --- helpers ---

    private List<RetrievedCase> validCases() {
        return List.of(new RetrievedCase(
                "IVA — Regime Geral",
                "Qual o regime de IVA para PME?",
                "As PME com volume de negócios inferior ao limiar aplicam o regime trimestral.",
                0.88));
    }

    private AIResponse stubResponse(String content) {
        return new AIResponse("stub", "stub", content, 0, 0, 0L);
    }
}
