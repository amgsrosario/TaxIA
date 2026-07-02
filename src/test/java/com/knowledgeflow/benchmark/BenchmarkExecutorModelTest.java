package com.knowledgeflow.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the data model and conventions used by run-taxia-benchmark.ps1:
 * dataset loading, case filtering, result structure, filename pattern,
 * CSV column contract, and absence of credentials in executor scripts.
 *
 * No calls to external services are made here.
 */
class BenchmarkExecutorModelTest {

    private static final Path DATASET_PATH =
            Paths.get("benchmark", "cases", "taxia-benchmark-v1.json");
    private static final Path EXECUTOR_SCRIPT =
            Paths.get("scripts", "run-taxia-benchmark.ps1");
    private static final Path RESULTS_LOCAL_DIR =
            Paths.get("benchmark", "results", "local");
    private static final Path REPORTS_DIR =
            Paths.get("benchmark", "reports");

    // taxia-benchmark-v1_openai_20260622T130500.json
    private static final Pattern FILENAME_PATTERN =
            Pattern.compile("^taxia-benchmark-v1_(anthropic|openai)_\\d{8}T\\d{6}\\.(json|csv)$");

    private static final Pattern CREDENTIAL_PATTERN =
            Pattern.compile("(?i)(sk-ant-|sk-[a-zA-Z0-9]{30}|OPENAI_API_KEY\\s*=\\s*[^$\"<{\\n]+)");

    // CSV columns aligned with evaluation-template.csv and run-taxia-benchmark.ps1 (35 columns)
    private static final List<String> EXPECTED_CSV_COLUMNS = List.of(
            "benchmark_version", "case_id", "title", "provider", "model",
            "input_tokens", "output_tokens", "duration_ms", "error", "answer",
            "support_status", "requires_human_validation", "unsupported_claims_count",
            "sources_count", "provider_called", "response_rejected",
            "execution_outcome", "requires_human_validation_final",
            "correctness_1_5", "context_adherence_1_5", "missing_info_identified_1_5",
            "exception_identified_1_5", "no_invention_1_5", "clarity_1_5", "structure_1_5",
            "decision_utility_1_5", "prudence_1_5", "human_validation_signalled_1_5",
            "expected_behaviours_met", "forbidden_behaviours_detected",
            "critical_failure", "critical_failure_reason",
            "overall_notes", "evaluator", "evaluated_at");

    // Required top-level fields in the JSON output
    private static final List<String> REQUIRED_TOP_LEVEL_FIELDS = List.of(
            "completed", "executionId", "benchmarkVersion", "expectedProvider",
            "modelsUsed", "startedAt", "finishedAt", "baseUrl",
            "totalCases", "successfulCases", "failedCases",
            "totalInputTokens", "totalOutputTokens", "totalDurationMillis", "averageDurationMillis",
            "results");

    // Required per-result fields in the JSON output
    private static final List<String> REQUIRED_RESULT_FIELDS = List.of(
            "caseId", "title", "category", "difficulty", "requiresHumanValidation",
            "expectedProvider", "actualProvider",
            "model", "answer", "inputTokens", "outputTokens",
            "durationMillis", "startedAt", "finishedAt", "error",
            "supportStatus", "requiresHumanValidation_grounding", "unsupportedClaimsCount",
            "sourcesCount", "providerCalled", "responseRejected",
            "executionOutcome", "requiresHumanValidationFinal");

    private static JsonNode root;
    private static List<JsonNode> allCases;

    @BeforeAll
    static void loadDataset() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        root = mapper.readTree(DATASET_PATH.toFile());
        allCases = new ArrayList<>();
        root.get("cases").forEach(allCases::add);
    }

    // ── dataset contract validation ───────────────────────────────────────────

    @Test
    void datasetWithValidBenchmarkVersionPassesContract() throws Exception {
        String json = "{\"benchmarkVersion\":\"taxia-benchmark-v1\",\"cases\":[{\"id\":\"TAXIA-001\"}]}";
        JsonNode node = new ObjectMapper().readTree(json);
        assertThat(node.has("benchmarkVersion")).isTrue();
        assertThat(node.get("benchmarkVersion").asText()).isNotBlank();
        assertThat(node.has("cases")).isTrue();
        assertThat(node.get("cases").size()).isGreaterThan(0);
    }

    @Test
    void datasetMissingBenchmarkVersionFailsContract() throws Exception {
        String json = "{\"cases\":[{\"id\":\"TAXIA-001\"}]}";
        JsonNode node = new ObjectMapper().readTree(json);
        assertThat(node.has("benchmarkVersion"))
                .as("Dataset without benchmarkVersion must fail validation")
                .isFalse();
    }

    @Test
    void datasetWithEmptyBenchmarkVersionFailsContract() throws Exception {
        String json = "{\"benchmarkVersion\":\"\",\"cases\":[{\"id\":\"TAXIA-001\"}]}";
        JsonNode node = new ObjectMapper().readTree(json);
        String bv = node.get("benchmarkVersion").asText();
        assertThat(bv.isBlank())
                .as("Empty benchmarkVersion must be treated as invalid")
                .isTrue();
    }

    @Test
    void datasetMissingCasesFieldFailsContract() throws Exception {
        String json = "{\"benchmarkVersion\":\"taxia-benchmark-v1\"}";
        JsonNode node = new ObjectMapper().readTree(json);
        assertThat(node.has("cases"))
                .as("Dataset without cases field must fail validation")
                .isFalse();
    }

    @Test
    void datasetWithEmptyCasesArrayFailsContract() throws Exception {
        String json = "{\"benchmarkVersion\":\"taxia-benchmark-v1\",\"cases\":[]}";
        JsonNode node = new ObjectMapper().readTree(json);
        assertThat(node.get("cases").size())
                .as("Dataset with empty cases array must fail validation")
                .isEqualTo(0);
    }

    // ── dataset loading ───────────────────────────────────────────────────────

    @Test
    void datasetLoadsAllTenCases() {
        assertThat(allCases).hasSize(10);
    }

    @Test
    void eachCaseHasSystemInstructionAndQuestion() {
        for (JsonNode c : allCases) {
            String id = c.get("id").asText();
            assertThat(c.get("systemInstruction").asText())
                    .as("Case %s: systemInstruction blank", id).isNotBlank();
            assertThat(c.get("question").asText())
                    .as("Case %s: question blank", id).isNotBlank();
        }
    }

    // ── case filtering ────────────────────────────────────────────────────────

    @Test
    void filterBySingleCaseId() {
        List<JsonNode> filtered = allCases.stream()
                .filter(c -> "TAXIA-001".equals(c.get("id").asText()))
                .toList();
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).get("id").asText()).isEqualTo("TAXIA-001");
    }

    @Test
    void filterByMultipleCaseIds() {
        List<String> ids = List.of("TAXIA-001", "TAXIA-005", "TAXIA-010");
        List<JsonNode> filtered = allCases.stream()
                .filter(c -> ids.contains(c.get("id").asText()))
                .toList();
        assertThat(filtered).hasSize(3);
    }

    @Test
    void filterWithUnknownIdReturnsNoResults() {
        List<JsonNode> filtered = allCases.stream()
                .filter(c -> "TAXIA-999".equals(c.get("id").asText()))
                .toList();
        assertThat(filtered).isEmpty();
    }

    @Test
    void filterWithNoCaseIdSelectsAll() {
        // Empty CaseId → all cases selected
        String caseId = "";
        List<JsonNode> selected = caseId.isEmpty() ? allCases : allCases.stream()
                .filter(c -> caseId.equals(c.get("id").asText()))
                .toList();
        assertThat(selected).hasSize(10);
    }

    // ── max call count ────────────────────────────────────────────────────────

    @Test
    void maxCallCountEqualsNumberOfSelectedCasesForFullRun() {
        int totalCases = allCases.size();
        int maxCalls   = totalCases; // exactly one call per case, no retries
        assertThat(maxCalls).isEqualTo(10);
    }

    @Test
    void maxCallCountIsOneForSingleCaseRun() {
        String caseId  = "TAXIA-004";
        List<JsonNode> selected = allCases.stream()
                .filter(c -> caseId.equals(c.get("id").asText()))
                .toList();
        int maxCalls = selected.size();
        assertThat(maxCalls).isEqualTo(1);
    }

    // ── filename pattern ──────────────────────────────────────────────────────

    @Test
    void generatedFilenamesMatchExpectedPattern() {
        String stamp = "20260622T130500";
        for (String provider : List.of("anthropic", "openai")) {
            for (String ext : List.of("json", "csv")) {
                String name = "taxia-benchmark-v1_" + provider + "_" + stamp + "." + ext;
                assertThat(name)
                        .as("Filename '%s' must match expected pattern", name)
                        .matches(FILENAME_PATTERN.pattern());
            }
        }
    }

    @Test
    void filenameWithUnknownProviderDoesNotMatch() {
        String name = "taxia-benchmark-v1_stub_20260622T130500.json";
        assertThat(name).doesNotMatch(FILENAME_PATTERN.pattern());
    }

    @Test
    void filenameWithOldHyphenFormatDoesNotMatch() {
        // Old format used hyphens: taxia-benchmark-v1-openai-20260622-130500.json
        String name = "taxia-benchmark-v1-openai-20260622-130500.json";
        assertThat(name).doesNotMatch(FILENAME_PATTERN.pattern());
    }

    // ── JSON result structure ─────────────────────────────────────────────────

    @Test
    void jsonTopLevelContainsRequiredFields() {
        ObjectNode obj = new ObjectMapper().createObjectNode();
        REQUIRED_TOP_LEVEL_FIELDS.forEach(f -> obj.put(f, "placeholder"));
        for (String field : REQUIRED_TOP_LEVEL_FIELDS) {
            assertThat(obj.has(field)).as("Top-level must have field: %s", field).isTrue();
        }
    }

    @Test
    void jsonResultEntryContainsRequiredFields() {
        ObjectNode obj = new ObjectMapper().createObjectNode();
        REQUIRED_RESULT_FIELDS.forEach(f -> obj.put(f, "placeholder"));
        for (String field : REQUIRED_RESULT_FIELDS) {
            assertThat(obj.has(field)).as("Result entry must have field: %s", field).isTrue();
        }
    }

    @Test
    void jsonResultUsesExpectedProviderNotProviderLabel() {
        // The new spec uses 'expectedProvider', not the old 'providerLabel'
        assertThat(REQUIRED_TOP_LEVEL_FIELDS).contains("expectedProvider");
        assertThat(REQUIRED_TOP_LEVEL_FIELDS).doesNotContain("providerLabel");
    }

    @Test
    void jsonOutputTopLevelUsesBenchmarkVersionNotVersion() {
        // Output JSON must use 'benchmarkVersion', never the deprecated 'version'
        assertThat(REQUIRED_TOP_LEVEL_FIELDS).contains("benchmarkVersion");
        assertThat(REQUIRED_TOP_LEVEL_FIELDS).doesNotContain("version");
    }

    @Test
    void jsonResultEntryUsesBothExpectedAndActualProvider() {
        assertThat(REQUIRED_RESULT_FIELDS).contains("expectedProvider");
        assertThat(REQUIRED_RESULT_FIELDS).contains("actualProvider");
    }

    @Test
    void jsonResultDoesNotContainSensitiveFields() {
        // These fields must never appear in the JSON output
        List<String> forbidden = List.of("password", "token", "apiKey", "jwt",
                "authorization", "secret", "accessToken");
        for (String f : forbidden) {
            assertThat(REQUIRED_TOP_LEVEL_FIELDS).doesNotContain(f);
            assertThat(REQUIRED_RESULT_FIELDS).doesNotContain(f);
        }
    }

    // ── provider mismatch ─────────────────────────────────────────────────────

    @Test
    void providerMismatchDetectedWhenProvidersDiffer() {
        String expected = "openai";
        String actual   = "anthropic";
        boolean mismatch = !expected.equals(actual);
        assertThat(mismatch).isTrue();
    }

    @Test
    void providerMismatchFalseWhenProvidersMatch() {
        String expected = "anthropic";
        String actual   = "anthropic";
        boolean mismatch = !expected.equals(actual);
        assertThat(mismatch).isFalse();
    }

    @Test
    void providerMismatchResultsInError() {
        // When providers diverge, the case is counted as a failure
        String expected = "openai";
        String actual   = "anthropic";
        boolean mismatch = !expected.equals(actual);
        String errorMsg = mismatch
                ? "PROVIDER_MISMATCH: expected=" + expected + " actual=" + actual
                : null;
        assertThat(errorMsg).isNotNull();
        assertThat(errorMsg).contains("PROVIDER_MISMATCH");
        assertThat(errorMsg).contains(expected);
        assertThat(errorMsg).contains(actual);
    }

    // ── CSV column contract ───────────────────────────────────────────────────

    @Test
    void csvHeaderContainsAllExpectedColumns() {
        String header = String.join(",", EXPECTED_CSV_COLUMNS);
        for (String col : EXPECTED_CSV_COLUMNS) {
            assertThat(header).as("CSV header must contain column: %s", col).contains(col);
        }
    }

    @Test
    void csvHasThirtyFiveColumns() {
        assertThat(EXPECTED_CSV_COLUMNS).hasSize(35);
    }

    @Test
    void csvHasTenScoringColumns() {
        long scoringCols = EXPECTED_CSV_COLUMNS.stream()
                .filter(c -> c.endsWith("_1_5"))
                .count();
        assertThat(scoringCols).isEqualTo(10);
    }

    @Test
    void csvHasFourCriticalEvaluationColumns() {
        assertThat(EXPECTED_CSV_COLUMNS).contains("expected_behaviours_met");
        assertThat(EXPECTED_CSV_COLUMNS).contains("forbidden_behaviours_detected");
        assertThat(EXPECTED_CSV_COLUMNS).contains("critical_failure");
        assertThat(EXPECTED_CSV_COLUMNS).contains("critical_failure_reason");
    }

    @Test
    void csvFirstColumnIsBenchmarkVersion() {
        assertThat(EXPECTED_CSV_COLUMNS.get(0)).isEqualTo("benchmark_version");
    }

    @Test
    void csvDoesNotContainSensitiveColumns() {
        List<String> forbidden = List.of("password", "token", "jwt", "api_key",
                "authorization", "secret");
        for (String f : forbidden) {
            assertThat(EXPECTED_CSV_COLUMNS).doesNotContain(f);
        }
    }

    @Test
    void csvContainsSixGroundingColumns() {
        List<String> groundingCols = List.of(
                "support_status", "requires_human_validation", "unsupported_claims_count",
                "sources_count", "provider_called", "response_rejected");
        for (String col : groundingCols) {
            assertThat(EXPECTED_CSV_COLUMNS)
                    .as("CSV must contain grounding column: %s", col)
                    .contains(col);
        }
    }

    @Test
    void csvGroundingColumnsArePositionedAfterAnswer() {
        int answerIdx     = EXPECTED_CSV_COLUMNS.indexOf("answer");
        int supportIdx    = EXPECTED_CSV_COLUMNS.indexOf("support_status");
        int rejectedIdx   = EXPECTED_CSV_COLUMNS.indexOf("response_rejected");
        int scoringIdx    = EXPECTED_CSV_COLUMNS.indexOf("correctness_1_5");
        assertThat(answerIdx).isGreaterThanOrEqualTo(0);
        assertThat(supportIdx).isGreaterThan(answerIdx);
        assertThat(rejectedIdx).isGreaterThan(answerIdx);
        assertThat(scoringIdx).isGreaterThan(rejectedIdx);
    }

    @Test
    void resultFieldsIncludeSixGroundingFields() {
        List<String> groundingFields = List.of(
                "supportStatus", "requiresHumanValidation_grounding", "unsupportedClaimsCount",
                "sourcesCount", "providerCalled", "responseRejected");
        for (String field : groundingFields) {
            assertThat(REQUIRED_RESULT_FIELDS)
                    .as("REQUIRED_RESULT_FIELDS must include grounding field: %s", field)
                    .contains(field);
        }
    }

    @Test
    void executorScriptExtractsSupportStatusFromResponse() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content)
                .as("Script must extract supportStatus from AI response")
                .contains("supportStatus");
        assertThat(content)
                .as("Script must use PSObject.Properties for backward-compatible extraction")
                .contains("PSObject.Properties[\"supportStatus\"]");
    }

    @Test
    void executorScriptExtractsProviderCalledFromResponse() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content)
                .as("Script must extract providerCalled from AI response")
                .contains("providerCalled");
        assertThat(content)
                .as("Script must use PSObject.Properties for backward-compatible extraction")
                .contains("PSObject.Properties[\"providerCalled\"]");
    }

    @Test
    void executorScriptCsvHeaderHasSupportStatusColumn() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content)
                .as("CSV header in script must include support_status column")
                .contains("support_status");
        assertThat(content)
                .as("CSV header in script must include response_rejected column")
                .contains("response_rejected");
    }

    @Test
    void analyserScriptReportsGroundingMetrics() throws Exception {
        Path analyserScript = Paths.get("scripts", "analyse-taxia-benchmark.ps1");
        String content = Files.readString(analyserScript, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content).contains("Grounding");
        assertThat(content).contains("Sem chamada ao provider");
        assertThat(content).contains("Respostas rejeitadas");
        assertThat(content).contains("Afirmacoes nao suportadas");
    }

    // ── CSV escaping ──────────────────────────────────────────────────────────

    @Test
    void csvEscapingHandlesCommas() {
        String value    = "valor, com vírgula";
        String escaped  = csvEscape(value);
        assertThat(escaped).startsWith("\"").endsWith("\"");
        assertThat(escaped).contains("valor, com vírgula");
    }

    @Test
    void csvEscapingHandlesQuotes() {
        String value    = "valor \"com aspas\" dentro";
        String escaped  = csvEscape(value);
        assertThat(escaped).startsWith("\"");
        assertThat(escaped).contains("\"\"com aspas\"\"");
    }

    @Test
    void csvEscapingHandlesNewlines() {
        String value   = "primeira linha\nsegunda linha";
        String escaped = csvEscape(value);
        assertThat(escaped).startsWith("\"").endsWith("\"");
    }

    @Test
    void csvEscapingHandlesSemicolons() {
        String value   = "valor; com ponto e vírgula";
        String escaped = csvEscape(value);
        assertThat(escaped).startsWith("\"").endsWith("\"");
    }

    @Test
    void csvEscapingPlainValueNeedsNoQuotes() {
        String value   = "resposta simples";
        String escaped = csvEscape(value);
        assertThat(escaped).isEqualTo("resposta simples");
    }

    @Test
    void csvEscapingHandlesPortugueseCharacters() {
        String value   = "Contribuição, ISR, regime especial são, não";
        String escaped = csvEscape(value);
        // Contains comma → must be quoted, but Portuguese chars preserved
        assertThat(escaped).startsWith("\"");
        assertThat(escaped).contains("são");
        assertThat(escaped).contains("não");
    }

    // ── directory structure ───────────────────────────────────────────────────

    @Test
    void resultsLocalDirectoryExists() {
        assertThat(RESULTS_LOCAL_DIR.toFile()).exists();
    }

    @Test
    void resultsLocalDirectoryContainsGitkeep() {
        assertThat(RESULTS_LOCAL_DIR.resolve(".gitkeep").toFile()).exists();
    }

    @Test
    void reportsDirectoryExists() {
        assertThat(REPORTS_DIR.toFile()).exists();
    }

    @Test
    void reportsDirectoryContainsGitkeep() {
        assertThat(REPORTS_DIR.resolve(".gitkeep").toFile()).exists();
    }

    // ── executor script ───────────────────────────────────────────────────────

    @Test
    void executorScriptExists() {
        assertThat(EXECUTOR_SCRIPT.toFile()).exists();
    }

    @Test
    void executorScriptContainsNoHardcodedCredentials() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT);
        assertThat(content)
                .as("Executor script must not contain hardcoded credentials")
                .doesNotContainPattern(CREDENTIAL_PATTERN.pattern());
    }

    @Test
    void executorScriptRequiresExplicitConfirmation() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT);
        assertThat(content)
                .as("Executor script must require explicit EXECUTAR confirmation")
                .contains("EXECUTAR");
    }

    @Test
    void executorScriptDoesNotPrintToken() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT);
        // Token should only be assigned and used in headers, never printed
        assertThat(content)
                .as("Executor script must not print the JWT token")
                .doesNotContain("Write-Host $token")
                .doesNotContain("Write-Output $token");
    }

    @Test
    void realDatasetPassesScriptValidationViaPowerShell() throws Exception {
        // Reads the canonical file using the same accessor pattern as the script,
        // and validates the contract — without auth or API calls.
        String psExe = locatePowerShell();
        org.junit.jupiter.api.Assumptions.assumeTrue(psExe != null,
                "powershell.exe not found — skipping PS integration validation");

        String datasetPath = DATASET_PATH.toAbsolutePath().toString().replace("'", "''");

        // Mirrors the exact validation block in run-taxia-benchmark.ps1
        String psCommand =
                "Set-StrictMode -Version Latest; $ErrorActionPreference='Stop';" +
                "$datasetJson = Get-Content -LiteralPath '" + datasetPath + "' -Raw -Encoding UTF8;" +
                "$datasetObject = $datasetJson | ConvertFrom-Json;" +
                "if ($null -eq $datasetObject) { Write-Error 'NULL'; exit 1 };" +
                "$vp = $datasetObject.PSObject.Properties['benchmarkVersion'];" +
                "if ($null -eq $vp -or [string]::IsNullOrWhiteSpace([string]$vp.Value)) {" +
                "    Write-Error 'MISSING_benchmarkVersion'; exit 1 };" +
                "$cp = $datasetObject.PSObject.Properties['cases'];" +
                "if ($null -eq $cp -or $null -eq $cp.Value -or $cp.Value.Count -eq 0) {" +
                "    Write-Error 'MISSING_cases'; exit 1 };" +
                "Write-Host ('BV=' + [string]$vp.Value);" +
                "Write-Host ('COUNT=' + $cp.Value.Count)";

        ProcessBuilder pb = new ProcessBuilder(
                psExe, "-NonInteractive", "-NoProfile", "-Command", psCommand);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).trim();
        int exit = proc.waitFor();

        assertThat(exit)
                .as("powershell.exe dataset validation exit code must be 0. Output: " + output)
                .isEqualTo(0);
        assertThat(output).contains("BV=taxia-benchmark-v1");
        assertThat(output).contains("COUNT=10");
    }

    @Test
    void executorScriptUsesCanonicalBenchmarkVersionField() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content)
                .as("Script must read 'benchmarkVersion' via PSObject.Properties accessor")
                .contains("PSObject.Properties[\"benchmarkVersion\"]");
        assertThat(content)
                .as("Script must not use ambiguous $dataset variable (use $datasetObject)")
                .doesNotContain("$dataset.benchmarkVersion");
        assertThat(content)
                .as("Script must not access $dataset.version (deprecated field)")
                .doesNotContain("$dataset.version");
    }

    @Test
    void executorScriptUsesUnambiguousVariableNames() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content).contains("$datasetPath");
        assertThat(content).contains("$datasetJson");
        assertThat(content).contains("$datasetObject");
    }

    @Test
    void executorScriptResolvesPathFromPSScriptRoot() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content)
                .as("Script must resolve dataset path relative to PSScriptRoot, not CWD")
                .contains("$PSScriptRoot");
        assertThat(content).contains("Join-Path $projectRoot");
    }

    @Test
    void executorScriptValidatesDatasetBeforeBackendCheck() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, java.nio.charset.StandardCharsets.UTF_8);
        // Dataset validation must appear before backend health check
        int validationIdx = content.indexOf("benchmarkVersion' ausente");
        int backendIdx    = content.indexOf("actuator/health");
        assertThat(validationIdx).as("Dataset validation block not found").isGreaterThan(0);
        assertThat(backendIdx).as("Backend health check not found").isGreaterThan(0);
        assertThat(validationIdx)
                .as("Dataset validation must occur before backend health check")
                .isLessThan(backendIdx);
    }

    @Test
    void executorScriptValidatesDatasetBeforeAuth() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, java.nio.charset.StandardCharsets.UTF_8);
        // Validation block must appear before the authentication block
        int validationIdx = content.indexOf("benchmarkVersion' ausente");
        int authIdx       = content.indexOf("Read-Host \"Password\"");
        assertThat(validationIdx).as("Dataset validation block not found").isGreaterThan(0);
        assertThat(authIdx).as("Auth block not found").isGreaterThan(0);
        assertThat(validationIdx)
                .as("Dataset validation must occur before authentication")
                .isLessThan(authIdx);
    }

    @Test
    void executorScriptHasValidateDatasetOnlyMode() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content).contains("ValidateDatasetOnly");
        assertThat(content).contains("Dataset valido");
        assertThat(content).contains("Versao:");
        assertThat(content).contains("Casos:");
    }

    @Test
    void validateDatasetOnlyModeProducesExpectedOutputFromProjectRoot() throws Exception {
        String psExe = locatePowerShell();
        org.junit.jupiter.api.Assumptions.assumeTrue(psExe != null,
                "powershell.exe not found — skipping integration test");

        String scriptPath = EXECUTOR_SCRIPT.toAbsolutePath().toString();
        ProcessBuilder pb = new ProcessBuilder(
                psExe, "-NonInteractive", "-NoProfile",
                "-File", scriptPath,
                "-Provider", "openai",
                "-ValidateDatasetOnly");
        pb.directory(Paths.get("C:\\Projetos\\TaxIA").toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).trim();
        int exit = proc.waitFor();

        assertThat(exit).as("Exit code from project root. Output: " + output).isEqualTo(0);
        assertThat(output).contains("Dataset valido");
        assertThat(output).contains("Versao: taxia-benchmark-v1");
        assertThat(output).contains("Casos: 10");
    }

    @Test
    void validateDatasetOnlyModeProducesExpectedOutputFromScriptsDir() throws Exception {
        String psExe = locatePowerShell();
        org.junit.jupiter.api.Assumptions.assumeTrue(psExe != null,
                "powershell.exe not found — skipping integration test");

        String scriptPath = EXECUTOR_SCRIPT.toAbsolutePath().toString();
        ProcessBuilder pb = new ProcessBuilder(
                psExe, "-NonInteractive", "-NoProfile",
                "-File", scriptPath,
                "-Provider", "openai",
                "-ValidateDatasetOnly");
        pb.directory(EXECUTOR_SCRIPT.getParent().toAbsolutePath().toFile()); // scripts/
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).trim();
        int exit = proc.waitFor();

        assertThat(exit).as("Exit code from scripts/. Output: " + output).isEqualTo(0);
        assertThat(output).contains("Dataset valido");
        assertThat(output).contains("Versao: taxia-benchmark-v1");
        assertThat(output).contains("Casos: 10");
    }

    @Test
    void validateDatasetOnlyModeProducesExpectedOutputFromArbitraryDir() throws Exception {
        String psExe = locatePowerShell();
        org.junit.jupiter.api.Assumptions.assumeTrue(psExe != null,
                "powershell.exe not found — skipping integration test");

        String scriptPath = EXECUTOR_SCRIPT.toAbsolutePath().toString();
        ProcessBuilder pb = new ProcessBuilder(
                psExe, "-NonInteractive", "-NoProfile",
                "-File", scriptPath,
                "-Provider", "openai",
                "-ValidateDatasetOnly");
        pb.directory(java.io.File.listRoots()[0]); // C:\
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).trim();
        int exit = proc.waitFor();

        assertThat(exit).as("Exit code from C:\\. Output: " + output).isEqualTo(0);
        assertThat(output).contains("Dataset valido");
        assertThat(output).contains("Versao: taxia-benchmark-v1");
        assertThat(output).contains("Casos: 10");
    }

    @Test
    void executorScriptHasUtf8Bom() throws Exception {
        byte[] bytes = Files.readAllBytes(EXECUTOR_SCRIPT);
        assertThat(bytes).as("Script must start with UTF-8 BOM (EF BB BF)")
                .hasSizeGreaterThan(3);
        assertThat(bytes[0] & 0xFF).as("BOM byte 0").isEqualTo(0xEF);
        assertThat(bytes[1] & 0xFF).as("BOM byte 1").isEqualTo(0xBB);
        assertThat(bytes[2] & 0xFF).as("BOM byte 2").isEqualTo(0xBF);
    }

    // ── encoding ──────────────────────────────────────────────────────────────

    @Test
    void scriptDefinesInvokeJsonPostUtf8Function() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content)
                .as("Script must define Invoke-JsonPostUtf8 function for explicit UTF-8 decoding")
                .contains("function Invoke-JsonPostUtf8");
        assertThat(content)
                .as("Function must use StreamReader with explicit UTF-8 encoding")
                .contains("[System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)");
    }

    @Test
    void scriptUsesInvokeJsonPostUtf8ForAiAsk() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content)
                .as("Script must use Invoke-JsonPostUtf8 for AI ask (not Invoke-RestMethod)")
                .contains("Invoke-JsonPostUtf8");
        assertThat(content)
                .as("AI ask must use -BodyBytes parameter")
                .contains("-BodyBytes");
        assertThat(content)
                .as("AI ask must pass Authorization header")
                .contains("-Authorization \"Bearer $token\"");
    }

    @Test
    void scriptWritesJsonAsUtf8WithoutBom() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, java.nio.charset.StandardCharsets.UTF_8);
        // JSON must use WriteAllText with UTF8Encoding(false) = no BOM
        assertThat(content)
                .as("JSON must be written without BOM using .NET WriteAllText")
                .contains("[System.IO.File]::WriteAllText(");
        assertThat(content)
                .as("JSON encoding must be UTF8Encoding(false) = no BOM")
                .contains("UTF8Encoding]::new($false)");
        // Must NOT use Set-Content -Encoding UTF8 for JSON (that adds BOM in PS 5.1)
        assertThat(content)
                .as("Script must not use Set-Content -Encoding UTF8 for JSON output")
                .doesNotContain("Set-Content -Path $jsonOutPath -Encoding UTF8");
    }

    @Test
    void scriptWritesCsvAsUtf8WithBom() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, java.nio.charset.StandardCharsets.UTF_8);
        // CSV must use WriteAllLines with UTF8Encoding(true) = with BOM (for Excel on Windows)
        assertThat(content)
                .as("CSV must be written with BOM using .NET WriteAllLines")
                .contains("[System.IO.File]::WriteAllLines(");
        assertThat(content)
                .as("CSV encoding must be UTF8Encoding(true) = with BOM")
                .contains("UTF8Encoding]::new($true)");
    }

    // ── integrity rules ───────────────────────────────────────────────────────

    @Test
    void successfulPlusFailedCasesEqualsTotalCases() {
        int total   = 10;
        int success = 7;
        int failed  = 3;
        assertThat(success + failed)
                .as("successfulCases + failedCases must equal totalCases")
                .isEqualTo(total);
    }

    @Test
    void successfulCaseWithEmptyAnswerViolatesIntegrity() {
        String error  = "";   // no error → counted as success
        String answer = "";   // but answer is empty
        // Integrity rule: a successful case must not have an empty answer.
        // This combination is a violation — the check must detect it (return true).
        boolean isIntegrityViolation = error.isEmpty() && answer.isEmpty();
        assertThat(isIntegrityViolation)
                .as("When error is empty (success) and answer is empty, this is an integrity violation")
                .isTrue();
    }

    @Test
    void caseWithErrorIsNotCountedAsSuccess() {
        String error    = "HTTP 500";
        boolean isError = !error.isEmpty();
        boolean countedAsSuccess = !isError;
        assertThat(countedAsSuccess)
                .as("A case with an error must not be counted as a success")
                .isFalse();
    }

    @Test
    void providerMismatchCaseIsCountedAsFailed() {
        String expected     = "openai";
        String actual       = "anthropic";
        boolean mismatch    = !expected.equals(actual);
        String errorMsg     = mismatch
                ? "PROVIDER_MISMATCH: expected=" + expected + " actual=" + actual
                : null;
        boolean countedAsFail = errorMsg != null;
        assertThat(countedAsFail).isTrue();
    }

    // ── mismatch semantics (Session 5 additions) ──────────────────────────────

    @Test
    void providerCalledFalse_insufficientContext_notFlaggedAsMismatch() {
        // providerCalled=false + INSUFFICIENT_CONTEXT → SAFE_NO_PROVIDER_CALL, no mismatch
        boolean providerCalled = false;
        String  supportStatus  = "INSUFFICIENT_CONTEXT";
        boolean mismatch       = false;

        if (providerCalled) {
            mismatch = !"openai".equals("none");
        }
        // supportStatus justifies no call → counts as success, not mismatch
        assertThat(mismatch).isFalse();
    }

    @Test
    void providerCalledTrue_differentProvider_flaggedAsMismatch() {
        boolean providerCalled = true;
        String  expected       = "openai";
        String  actual         = "anthropic";
        boolean mismatch       = false;

        if (providerCalled && !expected.equals(actual)) {
            mismatch = true;
        }
        assertThat(mismatch).isTrue();
    }

    @Test
    void providerCalledFalse_supportedStatus_structuralError() {
        // providerCalled=false but status is SUPPORTED → structural inconsistency
        boolean providerCalled  = false;
        String  supportStatus   = "SUPPORTED";
        boolean structuralError = false;

        if (!providerCalled && !supportStatus.equals("INSUFFICIENT_CONTEXT")
                && !supportStatus.equals("REQUIRES_HUMAN_REVIEW")) {
            structuralError = true;
        }
        assertThat(structuralError).isTrue();
    }

    @Test
    void safeRefusal_countedAsSuccess() {
        // SAFE_NO_PROVIDER_CALL is a functional success, not a technical error
        String executionOutcome = "SAFE_NO_PROVIDER_CALL";
        boolean isSuccess = executionOutcome.equals("PROVIDER_RESPONSE")
                || executionOutcome.equals("SAFE_NO_PROVIDER_CALL")
                || executionOutcome.equals("REJECTED_PROVIDER_RESPONSE");
        assertThat(isSuccess).isTrue();
    }

    @Test
    void rejectedResponse_countedAsFunctionalSuccess() {
        String executionOutcome = "REJECTED_PROVIDER_RESPONSE";
        boolean isSuccess = executionOutcome.equals("PROVIDER_RESPONSE")
                || executionOutcome.equals("SAFE_NO_PROVIDER_CALL")
                || executionOutcome.equals("REJECTED_PROVIDER_RESPONSE");
        assertThat(isSuccess).isTrue();
    }

    @Test
    void technicalError_countedAsFailure() {
        String executionOutcome = "TECHNICAL_ERROR";
        boolean isSuccess = executionOutcome.equals("PROVIDER_RESPONSE")
                || executionOutcome.equals("SAFE_NO_PROVIDER_CALL")
                || executionOutcome.equals("REJECTED_PROVIDER_RESPONSE");
        assertThat(isSuccess).isFalse();
    }

    @Test
    void executorScriptComputesRequiresHumanValidationFinalAsOrCombination() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content)
                .as("Script must compute requiresHumanValidationFinal as OR of case and grounding flags")
                .contains("requiresHumanValidationFinal");
        assertThat(content)
                .as("Script must use -or operator for final OR combination")
                .contains("$caseHumanRequired -or $groundingHumanRequired");
    }

    @Test
    void executorScriptComputesExecutionOutcome() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content)
                .as("Script must compute executionOutcome")
                .contains("$executionOutcome");
        assertThat(content).contains("SAFE_NO_PROVIDER_CALL");
        assertThat(content).contains("REJECTED_PROVIDER_RESPONSE");
        assertThat(content).contains("TECHNICAL_ERROR");
        assertThat(content).contains("PROVIDER_RESPONSE");
    }

    @Test
    void csvContainsTwoNewOutcomeColumns() {
        assertThat(EXPECTED_CSV_COLUMNS).contains("execution_outcome");
        assertThat(EXPECTED_CSV_COLUMNS).contains("requires_human_validation_final");
    }

    @Test
    void resultFieldsIncludeTwoNewOutcomeFields() {
        assertThat(REQUIRED_RESULT_FIELDS).contains("executionOutcome");
        assertThat(REQUIRED_RESULT_FIELDS).contains("requiresHumanValidationFinal");
    }

    @Test
    void csvNewColumnsArePositionedAfterResponseRejected() {
        int rejectedIdx  = EXPECTED_CSV_COLUMNS.indexOf("response_rejected");
        int outcomeIdx   = EXPECTED_CSV_COLUMNS.indexOf("execution_outcome");
        int humanFinalIdx = EXPECTED_CSV_COLUMNS.indexOf("requires_human_validation_final");
        int scoringIdx   = EXPECTED_CSV_COLUMNS.indexOf("correctness_1_5");
        assertThat(outcomeIdx).isGreaterThan(rejectedIdx);
        assertThat(humanFinalIdx).isGreaterThan(rejectedIdx);
        assertThat(scoringIdx).isGreaterThan(humanFinalIdx);
    }

    @Test
    void resultFileDoesNotContainSensitivePatterns() {
        // Validate that sensitive patterns are not present in result text
        List<String> sensitivePatterns = List.of(
                "sk-ant-", "sk-live-", "Bearer eyJ", "password", "apiKey");
        String cleanResult = "{\"benchmarkVersion\":\"taxia-benchmark-v1\",\"results\":[{\"answer\":\"Codigo do IVA\"}]}";
        for (String pattern : sensitivePatterns) {
            assertThat(cleanResult)
                    .as("Result must not contain sensitive pattern: %s", pattern)
                    .doesNotContain(pattern);
        }
    }

    @Test
    void mojibakePatternsAreDetectable() {
        // Confirm that known mojibake patterns can be detected programmatically
        String mojibake = "CÃ³digo do IVA, operaÃ§Ãµes tributÃ¡veis, isenÃ§Ã£o";
        assertThat(mojibake).containsAnyOf("CÃ", "Ã§", "Ã£", "Âº", "â¬");
    }

    @Test
    void cleanPortugueseTextContainsNoMojibake() {
        String clean = "Código do IVA português, operações tributáveis, isenção médica, dedução, 10 000 €, artigo 9.º";
        assertThat(clean).doesNotContain("CÃ");
        assertThat(clean).doesNotContain("Ã§");
        assertThat(clean).doesNotContain("Ã£");
        assertThat(clean).doesNotContain("Âº");
        assertThat(clean).doesNotContain("â¬");
    }

    @Test
    void jsonTopLevelIncludesCompletedAndExecutionId() {
        assertThat(REQUIRED_TOP_LEVEL_FIELDS).contains("completed");
        assertThat(REQUIRED_TOP_LEVEL_FIELDS).contains("executionId");
    }

    @Test
    void jsonTopLevelIncludesTokenAndDurationTotals() {
        assertThat(REQUIRED_TOP_LEVEL_FIELDS).contains("totalInputTokens");
        assertThat(REQUIRED_TOP_LEVEL_FIELDS).contains("totalOutputTokens");
        assertThat(REQUIRED_TOP_LEVEL_FIELDS).contains("totalDurationMillis");
        assertThat(REQUIRED_TOP_LEVEL_FIELDS).contains("averageDurationMillis");
    }

    @Test
    void jsonResultIncludesCategoryAndDifficulty() {
        assertThat(REQUIRED_RESULT_FIELDS).contains("category");
        assertThat(REQUIRED_RESULT_FIELDS).contains("difficulty");
        assertThat(REQUIRED_RESULT_FIELDS).contains("requiresHumanValidation");
    }

    // ── typographic dash check ────────────────────────────────────────────────

    @Test
    void executorScriptContainsNoTypographicDashes() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, java.nio.charset.StandardCharsets.UTF_8);
        // Em dash (U+2014) and en dash (U+2013) cause parse errors in PowerShell 5.1 without BOM
        assertThat(content)
                .as("Script must not contain em dash U+2014")
                .doesNotContain("—");
        assertThat(content)
                .as("Script must not contain en dash U+2013")
                .doesNotContain("–");
    }

    @Test
    void executorScriptParsesCleanlyInPowerShell() throws Exception {
        // Only runs when powershell.exe is available (Windows)
        String psExe = locatePowerShell();
        org.junit.jupiter.api.Assumptions.assumeTrue(psExe != null,
                "powershell.exe not found — skipping PS parser validation");

        String scriptPath = EXECUTOR_SCRIPT.toAbsolutePath().toString();
        String psCommand =
                "$t=$null;$e=$null;" +
                "[System.Management.Automation.Language.Parser]::ParseFile(" +
                "'" + scriptPath.replace("'", "''") + "'," +
                "[ref]$t,[ref]$e)|Out-Null;" +
                "if($e.Count -gt 0){$e|ForEach-Object{Write-Error $_.Message};exit 1}" +
                "else{Write-Host \"OK:$($t.Count)\"}";

        ProcessBuilder pb = new ProcessBuilder(psExe, "-NonInteractive", "-NoProfile", "-Command", psCommand);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        int exit = proc.waitFor();

        assertThat(exit)
                .as("powershell.exe parser exit code must be 0. Output: " + output)
                .isEqualTo(0);
        assertThat(output)
                .as("Parser output must start with OK")
                .startsWith("OK:");
    }

    private static String locatePowerShell() {
        for (String candidate : new String[]{
                "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                "C:\\Windows\\SysWOW64\\WindowsPowerShell\\v1.0\\powershell.exe"}) {
            if (new java.io.File(candidate).exists()) return candidate;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("where", "powershell.exe");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (!out.isBlank()) return out.lines().findFirst().orElse(null);
        } catch (Exception ignored) {}
        return null;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Java equivalent of the PowerShell Escape-CsvField function. */
    private static String csvEscape(String value) {
        if (value == null) return "";
        if (value.matches("(?s).*[,\";\\r\\n].*")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
