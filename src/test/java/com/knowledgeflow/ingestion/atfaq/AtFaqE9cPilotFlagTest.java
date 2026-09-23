package com.knowledgeflow.ingestion.atfaq;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Proves the governed E9C pilot master switch defaults to off — Bloco E,
 * E9C-pilot-profile (PROMPT 90).
 *
 * <ul>
 *   <li>D — flag absent: {@code e9cPilotEnabled} binds to {@code false}.</li>
 *   <li>E — flag explicitly false: stays {@code false}.</li>
 *   <li>binding sanity — flag true: binds to {@code true} (proves the property is wired).</li>
 * </ul>
 */
class AtFaqE9cPilotFlagTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(EnableProps.class);

    @org.springframework.boot.context.properties.EnableConfigurationProperties(AtFaqProperties.class)
    static class EnableProps {
    }

    @Test
    @DisplayName("D: flag absent => e9cPilotEnabled is false")
    void defaultsToFalseWhenAbsent() {
        runner.run(ctx ->
                assertThat(ctx.getBean(AtFaqProperties.class).isE9cPilotEnabled()).isFalse());
    }

    @Test
    @DisplayName("E: flag explicitly false => stays false")
    void staysFalseWhenSetFalse() {
        runner.withPropertyValues("knowledgeflow.ingestion.at-faq.e9c-pilot-enabled=false")
                .run(ctx ->
                        assertThat(ctx.getBean(AtFaqProperties.class).isE9cPilotEnabled()).isFalse());
    }

    @Test
    @DisplayName("binding sanity: flag true => binds true")
    void bindsTrueWhenSetTrue() {
        runner.withPropertyValues("knowledgeflow.ingestion.at-faq.e9c-pilot-enabled=true")
                .run(ctx ->
                        assertThat(ctx.getBean(AtFaqProperties.class).isE9cPilotEnabled()).isTrue());
    }
}
