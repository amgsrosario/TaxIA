package com.knowledgeflow.ai.provider.anthropic;

import com.knowledgeflow.ai.AIProperties;
import com.knowledgeflow.ai.exception.AIConfigurationException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicProviderTest {

    private static AIProperties propsWithAnthropicConfig(String apiKey, String model) {
        return new AIProperties("anthropic", Map.of(
                "anthropic", new AIProperties.ProviderConfig(true, apiKey, model, 1024)
        ));
    }

    // ── Cenário B: enabled=true, API key ausente ──────────────────────────────

    @Test
    void constructorThrowsWhenApiKeyIsBlank() {
        assertThatThrownBy(() -> new AnthropicProvider(propsWithAnthropicConfig("", "claude-haiku-4-5-20251001")))
                .isInstanceOf(AIConfigurationException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    void constructorThrowsWhenApiKeyIsNull() {
        assertThatThrownBy(() -> new AnthropicProvider(propsWithAnthropicConfig(null, "claude-haiku-4-5-20251001")))
                .isInstanceOf(AIConfigurationException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    void constructorThrowsWhenApiKeyIsWhitespaceOnly() {
        assertThatThrownBy(() -> new AnthropicProvider(propsWithAnthropicConfig("   ", "claude-haiku-4-5-20251001")))
                .isInstanceOf(AIConfigurationException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    // ── Modelo não configurado ────────────────────────────────────────────────

    @Test
    void constructorThrowsWhenModelIsBlank() {
        assertThatThrownBy(() -> new AnthropicProvider(propsWithAnthropicConfig("sk-ant-key", "")))
                .isInstanceOf(AIConfigurationException.class)
                .hasMessageContaining("model");
    }

    // ── Secção de configuração ausente ────────────────────────────────────────

    @Test
    void constructorThrowsWhenAnthropicConfigSectionIsMissing() {
        AIProperties props = new AIProperties("anthropic", Map.of());

        assertThatThrownBy(() -> new AnthropicProvider(props))
                .isInstanceOf(AIConfigurationException.class);
    }

    @Test
    void constructorThrowsWhenProvidersMapIsNull() {
        AIProperties props = new AIProperties("anthropic", null);

        assertThatThrownBy(() -> new AnthropicProvider(props))
                .isInstanceOf(AIConfigurationException.class);
    }
}
