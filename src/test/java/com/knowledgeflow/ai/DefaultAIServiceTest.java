package com.knowledgeflow.ai;

import com.knowledgeflow.ai.exception.AIAuthenticationException;
import com.knowledgeflow.ai.exception.AIConfigurationException;
import com.knowledgeflow.ai.exception.AIProviderException;
import com.knowledgeflow.ai.exception.AIRateLimitException;
import com.knowledgeflow.common.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAIServiceTest {

    private AIProvider mockProvider;
    private AIProviderResolver resolver;
    private DefaultAIService service;

    @BeforeEach
    void setUp() {
        mockProvider = mock(AIProvider.class);
        when(mockProvider.providerId()).thenReturn("mock");
        resolver = mock(AIProviderResolver.class);
        when(resolver.resolve()).thenReturn(mockProvider);
        service = new DefaultAIService(resolver,
                new com.knowledgeflow.common.observability.KnowledgeFlowMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    // ── Delegação e normalização ──────────────────────────────────────────────

    @Test
    void delegatesCompletelyToProvider() {
        AIResponse expected = new AIResponse("mock", "mock-model", "Resposta fiscal", 10, 20, 100L);
        when(mockProvider.generate(any())).thenReturn(expected);

        AIResponse result = service.complete(new AIRequest("Pergunta sobre IVA"));

        assertThat(result).isEqualTo(expected);
        verify(mockProvider).generate(any(AIRequest.class));
    }

    @Test
    void doesNotImportProviderSdkClasses() {
        assertThat(DefaultAIService.class.getDeclaredFields())
                .noneMatch(f -> f.getType().getName().contains("anthropic")
                        || f.getType().getName().contains("openai"));
    }

    @Test
    void requestCarriesTaskTypeForObservability() {
        AIResponse resp = new AIResponse("mock", "mock-model", "ok", 0, 0, 0L);
        when(mockProvider.generate(any())).thenReturn(resp);

        service.complete(new AIRequest("Pergunta"));

        verify(mockProvider).generate(argThat(req -> req.taskType() == AITaskType.STANDARD_RESPONSE));
    }

    @Test
    void responseNormalisationPreservesAllFields() {
        AIResponse providerResponse = new AIResponse("anthropic", "claude-haiku", "Conteúdo", 50, 100, 350L);
        when(mockProvider.generate(any())).thenReturn(providerResponse);

        AIResponse result = service.complete(new AIRequest("Pergunta"));

        assertThat(result.provider()).isEqualTo("anthropic");
        assertThat(result.modelUsed()).isEqualTo("claude-haiku");
        assertThat(result.content()).isEqualTo("Conteúdo");
        assertThat(result.inputTokens()).isEqualTo(50);
        assertThat(result.outputTokens()).isEqualTo(100);
        assertThat(result.durationMillis()).isEqualTo(350L);
    }

    // ── Conversão de erros de provider ───────────────────────────────────────

    @Test
    void convertsAuthenticationExceptionToBusinessException() {
        when(mockProvider.generate(any())).thenThrow(new AIAuthenticationException("Invalid key"));

        assertThatThrownBy(() -> service.complete(new AIRequest("Pergunta")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void convertsRateLimitExceptionToBusinessException() {
        when(mockProvider.generate(any())).thenThrow(new AIRateLimitException("Rate limit"));

        assertThatThrownBy(() -> service.complete(new AIRequest("Pergunta")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void convertsGenericProviderExceptionToBusinessException() {
        when(mockProvider.generate(any())).thenThrow(new AIProviderException("Generic error"));

        assertThatThrownBy(() -> service.complete(new AIRequest("Pergunta")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void businessExceptionPreservesOriginalCause() {
        AIProviderException original = new AIAuthenticationException("Invalid key");
        when(mockProvider.generate(any())).thenThrow(original);

        assertThatThrownBy(() -> service.complete(new AIRequest("Pergunta")))
                .isInstanceOf(BusinessException.class)
                .hasCause(original);
    }

    @Test
    void businessExceptionMessageIsStableAndDoesNotExposeInternalDetails() {
        when(mockProvider.generate(any())).thenThrow(new AIProviderException("internal sdk detail xyz-123"));

        assertThatThrownBy(() -> service.complete(new AIRequest("Pergunta")))
                .isInstanceOf(BusinessException.class)
                .hasMessageNotContaining("internal sdk detail xyz-123");
    }

    // ── Tratamento distinto de AIConfigurationException ──────────────────────

    @Test
    void configurationExceptionPropagatesWithoutConversionToBusinessException() {
        when(mockProvider.generate(any()))
                .thenThrow(new AIConfigurationException("Model not configured"));

        assertThatThrownBy(() -> service.complete(new AIRequest("Pergunta")))
                .isInstanceOf(AIConfigurationException.class)
                .isNotInstanceOf(BusinessException.class);
    }

    @Test
    void configurationExceptionIsNotTreatedAsExternalServiceError() {
        when(mockProvider.generate(any()))
                .thenThrow(new AIConfigurationException("Model not configured"));

        assertThatThrownBy(() -> service.complete(new AIRequest("Pergunta")))
                .isNotInstanceOf(BusinessException.class);
    }
}
