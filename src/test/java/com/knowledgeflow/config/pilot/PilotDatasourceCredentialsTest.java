package com.knowledgeflow.config.pilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * Proves the pilot profile requires PostgreSQL credentials as an explicit operational
 * decision — Bloco E, PROMPT 90-CORRECÇÃO (D-DB-USERNAME, D-DB-PASSWORD).
 *
 * <p>The datasource username and password in {@code application-pilot.yml} carry NO default
 * ({@code ${SPRING_DATASOURCE_USERNAME}} / {@code ${SPRING_DATASOURCE_PASSWORD}}), so the
 * username is never inferred from the database name and startup fails closed if either is
 * absent. These checks read the raw YAML and resolve placeholders in an isolated resolver
 * (only the YAML source, never the machine's real environment) so they are deterministic and
 * never touch the real {@code knowledgeflow_pilot} database.
 *
 * <ul>
 *   <li>A — username absent: {@code resolveRequiredPlaceholders} fails clearly.</li>
 *   <li>B — password absent: {@code resolveRequiredPlaceholders} fails clearly.</li>
 *   <li>C — both present: they resolve to the supplied values.</li>
 *   <li>hygiene — no literal credential is baked into the YAML (no {@code :default}).</li>
 * </ul>
 */
class PilotDatasourceCredentialsTest {

    private static PropertySource<?> pilotYaml() throws IOException {
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application-pilot", new ClassPathResource("application-pilot.yml"));
        return loaded.get(0);
    }

    private static PropertySourcesPropertyResolver resolverWith(PropertySource<?>... extra) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        for (PropertySource<?> s : extra) {
            sources.addFirst(s);
        }
        sources.addLast(pilotYaml());
        return new PropertySourcesPropertyResolver(sources);
    }

    @Test
    @DisplayName("hygiene: pilot YAML declares username/password with NO default (fail-closed)")
    void credentialsHaveNoDefaultInYaml() throws IOException {
        PropertySource<?> yaml = pilotYaml();
        assertThat(yaml.getProperty("spring.datasource.username"))
                .isEqualTo("${SPRING_DATASOURCE_USERNAME}");
        assertThat(yaml.getProperty("spring.datasource.password"))
                .isEqualTo("${SPRING_DATASOURCE_PASSWORD}");
    }

    @Test
    @DisplayName("A: username absent => placeholder resolution fails clearly")
    void usernameAbsentFailsClosed() throws IOException {
        PropertySourcesPropertyResolver resolver = resolverWith();
        assertThatThrownBy(() -> resolver.resolveRequiredPlaceholders("${SPRING_DATASOURCE_USERNAME}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPRING_DATASOURCE_USERNAME");
    }

    @Test
    @DisplayName("B: password absent => placeholder resolution fails clearly")
    void passwordAbsentFailsClosed() throws IOException {
        PropertySourcesPropertyResolver resolver = resolverWith();
        assertThatThrownBy(() -> resolver.resolveRequiredPlaceholders("${SPRING_DATASOURCE_PASSWORD}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPRING_DATASOURCE_PASSWORD");
    }

    @Test
    @DisplayName("C: username/password present => resolve to the supplied values")
    void credentialsResolveWhenPresent() throws IOException {
        PropertySourcesPropertyResolver resolver = resolverWith(new MapPropertySource("synthetic-env", Map.of(
                "SPRING_DATASOURCE_USERNAME", "pilot_synthetic_user",
                "SPRING_DATASOURCE_PASSWORD", "pilot_synthetic_password")));
        assertThat(resolver.resolveRequiredPlaceholders("${SPRING_DATASOURCE_USERNAME}"))
                .isEqualTo("pilot_synthetic_user");
        assertThat(resolver.resolveRequiredPlaceholders("${SPRING_DATASOURCE_PASSWORD}"))
                .isEqualTo("pilot_synthetic_password");
    }
}
