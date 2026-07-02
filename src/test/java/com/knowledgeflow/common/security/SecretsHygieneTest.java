package com.knowledgeflow.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Automated secrets hygiene: no real keys in the repository configuration,
 * secret-bearing properties always come from environment placeholders, and the
 * Actuator never exposes environment/config endpoints.
 */
class SecretsHygieneTest {

    private static final Path MAIN_RESOURCES = Path.of("src", "main", "resources");

    /** Patterns of real-looking credentials that must never appear in config. */
    private static final Pattern REAL_KEY_PATTERN = Pattern.compile(
            "sk-ant-[A-Za-z0-9]|sk-proj-[A-Za-z0-9]|AKIA[0-9A-Z]{16}|-----BEGIN (RSA )?PRIVATE KEY-----");

    /** Secret-bearing yml keys — value must be an env placeholder or empty. */
    private static final Pattern SECRET_PROPERTY = Pattern.compile(
            "^\\s*(api-key|secret|password)\\s*:\\s*(.+)$");

    @Test
    void noRealLookingKeysInMainResources() throws IOException {
        for (Path file : ymlAndPropertiesFiles()) {
            String content = Files.readString(file);
            assertThat(REAL_KEY_PATTERN.matcher(content).find())
                    .as("Ficheiro %s não pode conter chaves reais", file)
                    .isFalse();
        }
    }

    @Test
    void secretPropertiesInApplicationYmlComeFromEnvironment() throws IOException {
        List<String> lines = Files.readAllLines(MAIN_RESOURCES.resolve("application.yml"));
        for (String line : lines) {
            var matcher = SECRET_PROPERTY.matcher(line);
            if (matcher.matches()) {
                String value = matcher.group(2).trim();
                assertThat(value)
                        .as("Propriedade sensível deve vir do ambiente: %s", line.trim())
                        .satisfiesAnyOf(
                                v -> assertThat(v).startsWith("${"),
                                v -> assertThat(v).isEmpty());
            }
        }
    }

    @Test
    void actuatorDoesNotExposeSensitiveEndpoints() throws IOException {
        String content = Files.readString(MAIN_RESOURCES.resolve("application.yml"));
        var exposureLine = content.lines()
                .filter(l -> l.contains("include:"))
                .filter(l -> l.contains("health"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("management exposure line not found"));

        assertThat(exposureLine)
                .doesNotContain("env")
                .doesNotContain("beans")
                .doesNotContain("configprops")
                .doesNotContain("mappings")
                .doesNotContain("heapdump")
                .doesNotContain("threaddump");
    }

    @Test
    void testProfilesUseOnlyFictitiousValues() throws IOException {
        Path testResources = Path.of("src", "test", "resources");
        if (!Files.exists(testResources)) {
            return;
        }
        try (Stream<Path> files = Files.walk(testResources)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                String content = Files.readString(file);
                assertThat(REAL_KEY_PATTERN.matcher(content).find())
                        .as("Recursos de teste %s não podem conter chaves reais", file)
                        .isFalse();
            }
        }
    }

    private List<Path> ymlAndPropertiesFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_RESOURCES)) {
            return files
                    .filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".properties"))
                    .toList();
        }
    }
}
