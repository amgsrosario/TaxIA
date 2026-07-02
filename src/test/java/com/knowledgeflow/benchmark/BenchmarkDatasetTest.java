package com.knowledgeflow.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkDatasetTest {

    // Canonical source — never duplicated into src/test/resources
    private static final Path DATASET_PATH =
            Paths.get("benchmark", "cases", "taxia-benchmark-v1.json");

    private static final Set<String> VALID_CATEGORIES = Set.of(
            "STANDARD_RESPONSE", "DEEP_ANALYSIS", "REVIEW", "CLASSIFICATION", "EXTRACTION");
    private static final Set<String> VALID_DIFFICULTIES = Set.of("LOW", "MEDIUM", "HIGH");
    private static final java.util.regex.Pattern ID_PATTERN =
            java.util.regex.Pattern.compile("^TAXIA-[0-9]{3}$");
    private static final java.util.regex.Pattern CREDENTIAL_PATTERN =
            java.util.regex.Pattern.compile("(?i)(sk-|password|api[_-]?key|secret|bearer|jwt)");

    private static JsonNode root;
    private static List<JsonNode> cases;

    @BeforeAll
    static void loadDataset() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertThat(DATASET_PATH.toFile())
                .as("Dataset file must exist at %s", DATASET_PATH)
                .exists();
        root = mapper.readTree(DATASET_PATH.toFile());
        cases = new ArrayList<>();
        root.get("cases").forEach(cases::add);
    }

    @Test
    void datasetIsWellFormedJson() {
        assertThat(root).isNotNull();
    }

    @Test
    void benchmarkVersionFieldIsDefined() {
        assertThat(root.has("benchmarkVersion"))
                .as("Dataset must have 'benchmarkVersion' field (not 'version')")
                .isTrue();
        assertThat(root.get("benchmarkVersion").asText()).isNotBlank();
    }

    @Test
    void datasetDoesNotUseDeprecatedVersionField() {
        assertThat(root.has("version"))
                .as("Dataset must not use deprecated 'version' field — use 'benchmarkVersion'")
                .isFalse();
    }

    @Test
    void benchmarkVersionMatchesFilename() {
        // The benchmarkVersion value must correspond to the canonical filename prefix
        String bv = root.get("benchmarkVersion").asText();
        assertThat(bv).startsWith("taxia-benchmark-v");
    }

    @Test
    void datasetContainsAtLeastOnCase() {
        assertThat(cases).isNotEmpty();
    }

    @Test
    void allCaseIdsAreUniqueAndMatchPattern() {
        Set<String> seen = new HashSet<>();
        for (JsonNode c : cases) {
            String id = c.get("id").asText();
            assertThat(id).matches(ID_PATTERN.pattern());
            assertThat(seen.add(id)).as("Duplicate case id: %s", id).isTrue();
        }
    }

    @Test
    void allRequiredFieldsArePresent() {
        List<String> required = List.of(
                "id", "title", "category", "difficulty",
                "systemInstruction", "question",
                "expectedBehaviours", "forbiddenBehaviours", "requiresHumanValidation");
        for (JsonNode c : cases) {
            String id = c.has("id") ? c.get("id").asText() : "(unknown)";
            for (String field : required) {
                assertThat(c.has(field))
                        .as("Case %s is missing required field: %s", id, field)
                        .isTrue();
            }
        }
    }

    @Test
    void allCategoryValuesAreValid() {
        for (JsonNode c : cases) {
            String id = c.get("id").asText();
            String cat = c.get("category").asText();
            assertThat(VALID_CATEGORIES)
                    .as("Case %s has invalid category: %s", id, cat)
                    .contains(cat);
        }
    }

    @Test
    void allDifficultyValuesAreValid() {
        for (JsonNode c : cases) {
            String id = c.get("id").asText();
            String diff = c.get("difficulty").asText();
            assertThat(VALID_DIFFICULTIES)
                    .as("Case %s has invalid difficulty: %s", id, diff)
                    .contains(diff);
        }
    }

    @Test
    void noEmptyCases() {
        for (JsonNode c : cases) {
            String id = c.get("id").asText();
            assertThat(c.get("question").asText())
                    .as("Case %s has blank question", id)
                    .isNotBlank();
            assertThat(c.get("systemInstruction").asText())
                    .as("Case %s has blank systemInstruction", id)
                    .isNotBlank();
            assertThat(c.get("expectedBehaviours").size())
                    .as("Case %s has no expectedBehaviours", id)
                    .isGreaterThan(0);
            assertThat(c.get("forbiddenBehaviours").size())
                    .as("Case %s has no forbiddenBehaviours", id)
                    .isGreaterThan(0);
        }
    }

    @Test
    void noCredentialsOrSensitiveDataInDataset() {
        String raw = root.toString();
        assertThat(raw)
                .as("Dataset must not contain credentials or sensitive patterns")
                .doesNotContainPattern(CREDENTIAL_PATTERN.pattern());
    }
}
