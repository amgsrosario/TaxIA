package com.knowledgeflow.config.pilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.ai.AIProperties;
import com.knowledgeflow.ai.AIProvider;
import com.knowledgeflow.ai.AIProviderResolver;
import com.knowledgeflow.ai.exception.AIConfigurationException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Proves the pilot profile makes NO silent AI (LLM) provider decision — Bloco E,
 * PROMPT 90-CORRECÇÃO (D-AI).
 *
 * <p>Binds {@code knowledgeflow.ai} exactly as it resolves under the pilot profile:
 * {@code application.yml} (base) overlaid by {@code application-pilot.yml}, with placeholders
 * resolved against those files only (isolated from the machine's real environment, so the
 * result is deterministic and no env var can accidentally satisfy a default). No Spring
 * context, no datasource, no network — the real {@code knowledgeflow_pilot} is never touched.
 *
 * <ul>
 *   <li>Anthropic is NOT inherited: {@code primary-provider} is blank and every provider,
 *       Anthropic included, is disabled by default.</li>
 *   <li>Fail-closed: that same (undecided) config makes {@link AIProviderResolver} throw at
 *       construction — i.e. startup would abort until an explicit AI decision is made.</li>
 * </ul>
 */
class PilotAiProviderDefaultsTest {

    private static AIProperties pilotAiProperties() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        // Pilot overlay has the highest precedence; base is the fallback — same as runtime.
        sources.addFirst(load("application-pilot.yml", "pilot"));
        sources.addLast(load("application.yml", "base"));
        Binder binder = new Binder(
                ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        return binder.bind("knowledgeflow.ai", AIProperties.class).get();
    }

    private static PropertySource<?> load(String file, String name) throws IOException {
        List<PropertySource<?>> loaded =
                new YamlPropertySourceLoader().load(name, new ClassPathResource(file));
        return loaded.get(0);
    }

    @Test
    @DisplayName("pilot does NOT inherit Anthropic: primary-provider blank, all providers disabled")
    void pilotDoesNotInheritAnthropic() throws IOException {
        AIProperties props = pilotAiProperties();

        assertThat(props.primaryProvider())
                .as("pilot must not silently choose a provider")
                .isBlank();
        assertThat(props.providers().get("anthropic").enabled())
                .as("Anthropic must not be enabled by inheritance in pilot")
                .isFalse();
        assertThat(props.providers().get("openai").enabled()).isFalse();
        assertThat(props.providers().get("stub").enabled()).isFalse();
    }

    @Test
    @DisplayName("pilot AI config fails closed in the resolver until an explicit decision is made")
    void pilotAiConfigFailsClosed() throws IOException {
        AIProperties props = pilotAiProperties();
        // No provider bean would be registered (all disabled) and no primary chosen.
        Map<String, AIProvider> noProviders = Map.of();

        assertThatThrownBy(() -> new AIProviderResolver(noProviders, props))
                .isInstanceOf(AIConfigurationException.class)
                .hasMessageContaining("primary-provider");
    }
}
