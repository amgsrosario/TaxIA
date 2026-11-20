package com.knowledgeflow.ai.spend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgeflow.ai.AIProvider;
import com.knowledgeflow.ai.AIProviderResolver;
import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.provider.anthropic.AnthropicProvider;
import com.knowledgeflow.ai.provider.openai.OpenAIProvider;
import com.knowledgeflow.ai.provider.stub.StubAIProvider;
import org.junit.jupiter.api.Test;

/** Porta {@link MeteredModelClient} sem autoridade financeira: nenhuma chamada facturável é enviada. */
class UnfundedModelClientTest {

    private static final AIRequest REQUEST = new AIRequest("sistema", "pergunta");

    private static UnfundedModelClient clientFor(AIProvider provider) {
        AIProviderResolver resolver = mock(AIProviderResolver.class);
        when(resolver.resolve()).thenReturn(provider);
        return new UnfundedModelClient(resolver);
    }

    @Test
    void stubIsCalledExactlyOnceWithoutCost() {
        StubAIProvider stub = spy(new StubAIProvider());
        AIResponse response = clientFor(stub).complete(REQUEST);
        assertThat(response.content()).startsWith(StubAIProvider.STUB_ANSWER_PREFIX);
        verify(stub, times(1)).generateSingleAttempt(REQUEST);
        verify(stub, times(1)).generate(REQUEST);
    }

    @Test
    void realProvidersAreRefusedBeforeAnyCall() {
        // Os fornecedores reais não declaram tentativa única nem são não facturáveis: recusa sem envio.
        for (Class<? extends AIProvider> type : java.util.List.of(AnthropicProvider.class, OpenAIProvider.class)) {
            AIProvider provider = mock(type, CALLS_REAL_METHODS);
            assertThatThrownBy(() -> clientFor(provider).complete(REQUEST))
                    .isInstanceOf(SpendRefusedException.class)
                    .hasMessageContaining("não enviada");
            verify(provider, never()).generate(any());
            verify(provider, never()).generateSingleAttempt(any());
        }
    }

    @Test
    void billableProviderWithSingleAttemptIsRefusedForLackOfAuthority() {
        AIProvider provider = mock(AIProvider.class);
        when(provider.providerId()).thenReturn("pago");
        when(provider.supportsSingleAttempt()).thenReturn(true);
        when(provider.billable()).thenReturn(true);
        assertThatThrownBy(() -> clientFor(provider).complete(REQUEST))
                .isInstanceOf(SpendRefusedException.class)
                .hasMessageContaining(UnfundedModelClient.NO_AUTHORITY);
        verify(provider, never()).generate(any());
        verify(provider, never()).generateSingleAttempt(any());
    }

    @Test
    void providerWithDefaultsIsTreatedAsBillableAndWithoutSingleAttempt() {
        // Um fornecedor novo que só implemente generate() herda billable=true e supportsSingleAttempt=false.
        AIProvider provider = new AIProvider() {
            @Override
            public String providerId() {
                return "novo";
            }

            @Override
            public AIResponse generate(AIRequest request) {
                throw new AssertionError("não pode ser chamado");
            }
        };
        assertThat(provider.billable()).isTrue();
        assertThat(provider.supportsSingleAttempt()).isFalse();
        assertThatThrownBy(() -> clientFor(provider).complete(REQUEST)).isInstanceOf(SpendRefusedException.class);
    }

    @Test
    void nonBillableProviderWithoutSingleAttemptIsRefused() {
        AIProvider provider = mock(AIProvider.class);
        when(provider.providerId()).thenReturn("local");
        when(provider.supportsSingleAttempt()).thenReturn(false);
        when(provider.billable()).thenReturn(false);
        assertThatThrownBy(() -> clientFor(provider).complete(REQUEST)).isInstanceOf(SpendRefusedException.class);
        verify(provider, never()).generate(any());
        verify(provider, never()).generateSingleAttempt(any());
    }
}
