package com.knowledgeflow.ai;

import com.knowledgeflow.ai.exception.AIConfigurationException;
import com.knowledgeflow.ai.provider.stub.StubAIProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AIProviderResolverTest {

    @Test
    void resolvesConfiguredProvider() {
        StubAIProvider stub = new StubAIProvider();
        AIProperties props = new AIProperties("stub", Map.of(
                "stub", new AIProperties.ProviderConfig(true, null, null, null)
        ));
        AIProviderResolver resolver = new AIProviderResolver(Map.of("stub", stub), props);

        AIProvider resolved = resolver.resolve();

        assertThat(resolved).isSameAs(stub);
    }

    @Test
    void constructorThrowsWhenPrimaryProviderNotInMap() {
        AIProperties props = new AIProperties("openai", Map.of());

        assertThatThrownBy(() -> new AIProviderResolver(Map.of(), props))
                .isInstanceOf(AIConfigurationException.class)
                .hasMessageContaining("openai");
    }

    @Test
    void throwsWhenPrimaryProviderNotConfigured() {
        AIProperties props = new AIProperties(null, Map.of());

        assertThatThrownBy(() -> new AIProviderResolver(Map.of(), props))
                .isInstanceOf(AIConfigurationException.class)
                .hasMessageContaining("primary-provider");
    }

    @Test
    void throwsWhenPrimaryProviderIsBlank() {
        AIProperties props = new AIProperties("   ", Map.of());

        assertThatThrownBy(() -> new AIProviderResolver(Map.of(), props))
                .isInstanceOf(AIConfigurationException.class);
    }

    @Test
    void errorMessageNamesMissingProviderAndListsAvailable() {
        StubAIProvider stub = new StubAIProvider();
        AIProperties props = new AIProperties("openai", Map.of());

        assertThatThrownBy(() -> new AIProviderResolver(Map.of("stub", stub), props))
                .isInstanceOf(AIConfigurationException.class)
                .hasMessageContaining("openai")
                .hasMessageContaining("stub");
    }

    @Test
    void errorMessageIndicatesNoProvidersWhenMapIsEmpty() {
        AIProperties props = new AIProperties("anthropic", Map.of());

        assertThatThrownBy(() -> new AIProviderResolver(Map.of(), props))
                .isInstanceOf(AIConfigurationException.class)
                .hasMessageContaining("anthropic")
                .hasMessageContaining("nenhum provider");
    }
}
