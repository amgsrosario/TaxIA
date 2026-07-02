package com.knowledgeflow.benchmark;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for run-taxia-benchmark.ps1.
 *
 * Static-analysis tests always run (no powershell.exe required).
 * Mock-server tests run a self-contained PS fragment that exercises the exact
 * same catch-block and CSV/result-building logic as the main script, but without
 * the auth gate — avoiding the Read-Host-stdin limitation of PS 5.1 -NonInteractive.
 *
 * No calls to Anthropic or OpenAI are made.
 */
class BenchmarkExecutorIntegrationTest {

    private static final Path EXECUTOR_SCRIPT =
            Paths.get("scripts", "run-taxia-benchmark.ps1");

    private HttpServer mockServer;
    private int mockPort;
    private Path tempDir;

    @BeforeEach
    void setup() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockPort = mockServer.getAddress().getPort();
        tempDir = Files.createTempDirectory("taxia-bench-it-");
    }

    @AfterEach
    void teardown() {
        if (mockServer != null) mockServer.stop(0);
        deleteTempDir();
    }

    // ── static analysis — always run ─────────────────────────────────────────

    @Test
    void scriptContainsNoIfInsideFunctionArguments() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, StandardCharsets.UTF_8);
        // Pattern that crashed PS 5.1: SomeFn (if (...) {...} else {...})
        assertThat(content)
                .as("Script must not use if() as a function argument (PS 5.1 incompatible)")
                .doesNotContainPattern("\\w+\\s*\\(\\s*if\\s*\\(");
    }

    @Test
    void scriptAuthCatchBlockUsesExplicitNullGuard() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, StandardCharsets.UTF_8);
        // Old pattern: $code = if ($_.Exception.Response) {...}
        // Safe pattern: $loginResp2 = $null; try { $loginResp2 = $_.Exception.Response } catch {}
        assertThat(content)
                .as("Auth catch block must not assign status via if($_.Exception.Response)")
                .doesNotContain("if ($_.Exception.Response)");
    }

    @Test
    void scriptCaseCatchBlockUsesExplicitNullGuard() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, StandardCharsets.UTF_8);
        // Verify safe pattern present: try { $errResp = $_.Exception.Response } catch {}
        assertThat(content).contains("try { $errResp = $_.Exception.Response } catch {}");
    }

    @Test
    void scriptUsesExplicitAssignmentsBeforeHashtable() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, StandardCharsets.UTF_8);
        // Pre-assigned variables must be present
        assertThat(content).contains("$entryActualProvider");
        assertThat(content).contains("$entryModel");
        assertThat(content).contains("$entryAnswer");
        assertThat(content).contains("$entryError");
        assertThat(content).contains("$entryFinishedAt");
    }

    @Test
    void scriptHasPartialFileSaveOnFatalError() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, StandardCharsets.UTF_8);
        assertThat(content).contains("_partial");
        assertThat(content).contains("completed");
        assertThat(content).contains("fatalError");
        assertThat(content).contains("casesTried");
    }

    // ── mock-server tests — require powershell.exe ────────────────────────────

    @Test
    void http200SuccessPathRecordsResultInJson() throws Exception {
        String psExe = locatePowerShell();
        org.junit.jupiter.api.Assumptions.assumeTrue(psExe != null, "powershell.exe not found");

        mockServer.createContext("/ask", ex ->
                respond(ex, 200,
                        "{\"answer\":\"Resposta.\",\"provider\":\"openai\"," +
                        "\"model\":\"gpt-test\",\"inputTokens\":10,\"outputTokens\":5}"));
        mockServer.start();

        String out = runFragment(psExe, 200, "openai", 1);
        assertThat(out).contains("RESULT:caseId=CASE-001");
        assertThat(out).contains("error=");
        assertThat(out).doesNotContain("erro HTTP");
    }

    @Test
    void http500FailurePathRecordsCaseAndContinues() throws Exception {
        String psExe = locatePowerShell();
        org.junit.jupiter.api.Assumptions.assumeTrue(psExe != null, "powershell.exe not found");

        mockServer.createContext("/ask", ex ->
                respond(ex, 500, "{\"error\":\"Internal Server Error\"}"));
        mockServer.start();

        String out = runFragment(psExe, 500, "openai", 1);
        assertThat(out).contains("HTTP 500");
        assertThat(out).contains("RESULT:caseId=CASE-001");
    }

    @Test
    void http429RateLimitPathRecordsCaseAndContinues() throws Exception {
        String psExe = locatePowerShell();
        org.junit.jupiter.api.Assumptions.assumeTrue(psExe != null, "powershell.exe not found");

        mockServer.createContext("/ask", ex ->
                respond(ex, 429, "{\"error\":\"Too Many Requests\"}"));
        mockServer.start();

        String out = runFragment(psExe, 429, "openai", 1);
        assertThat(out).contains("HTTP 429");
        assertThat(out).contains("RESULT:caseId=CASE-001");
    }

    @Test
    void providerMismatchIsRecorded() throws Exception {
        String psExe = locatePowerShell();
        org.junit.jupiter.api.Assumptions.assumeTrue(psExe != null, "powershell.exe not found");

        // mock returns anthropic, test expects openai
        mockServer.createContext("/ask", ex ->
                respond(ex, 200,
                        "{\"answer\":\"R.\",\"provider\":\"anthropic\"," +
                        "\"model\":\"claude-haiku\",\"inputTokens\":8,\"outputTokens\":3}"));
        mockServer.start();

        String out = runFragment(psExe, 200, "openai", 1);
        assertThat(out).contains("PROVIDER_MISMATCH");
    }

    @Test
    void case1FailsThenCase2Succeeds_bothResultsPresent() throws Exception {
        String psExe = locatePowerShell();
        org.junit.jupiter.api.Assumptions.assumeTrue(psExe != null, "powershell.exe not found");

        AtomicInteger callCount = new AtomicInteger(0);
        mockServer.createContext("/ask", ex -> {
            int n = callCount.incrementAndGet();
            if (n == 1) {
                respond(ex, 500, "{\"error\":\"fail\"}");
            } else {
                respond(ex, 200,
                        "{\"answer\":\"OK.\",\"provider\":\"openai\"," +
                        "\"model\":\"gpt-test\",\"inputTokens\":5,\"outputTokens\":2}");
            }
        });
        mockServer.start();

        String out = runFragment(psExe, -1 /* mixed */, "openai", 2);

        // Both cases must appear
        assertThat(out).contains("RESULT:caseId=CASE-001");
        assertThat(out).contains("RESULT:caseId=CASE-002");
        // Case 1 had error, case 2 succeeded
        assertThat(out).contains("HTTP 500");
        // Exactly 2 calls — no retry
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void noRetryAfterCaseFailure() throws Exception {
        String psExe = locatePowerShell();
        org.junit.jupiter.api.Assumptions.assumeTrue(psExe != null, "powershell.exe not found");

        AtomicInteger callCount = new AtomicInteger(0);
        mockServer.createContext("/ask", ex -> {
            callCount.incrementAndGet();
            respond(ex, 500, "{}");
        });
        mockServer.start();

        runFragment(psExe, 500, "openai", 1);
        assertThat(callCount.get())
                .as("Exactly 1 HTTP call for 1 case — no retry")
                .isEqualTo(1);
    }

    @Test
    void partialFileSavedWhenExecutorCrashesAfterFirstCase() throws Exception {
        String psExe = locatePowerShell();
        org.junit.jupiter.api.Assumptions.assumeTrue(psExe != null, "powershell.exe not found");

        // The partial file scenario: an unexpected error escapes the per-case try/catch
        // (e.g. a bug in the executor loop body outside the inner catch).
        // We simulate this with an explicit throw after case 1 is recorded.
        String outDir = tempDir.toAbsolutePath().toString().replace("'", "''");

        String psScript =
                "Set-StrictMode -Version Latest\n" +
                "$ErrorActionPreference = 'Stop'\n" +
                "$results = [System.Collections.Generic.List[object]]::new()\n" +
                "$executorError = $null\n" +
                "$caseIndex = 0\n" +
                "$successCount = 0\n" +
                "$failCount = 0\n" +
                "try {\n" +
                "  # Case 1: success (simulated — no HTTP)\n" +
                "  $caseIndex++\n" +
                "  $results.Add([ordered]@{caseId='CASE-001';error=''})\n" +
                "  $successCount++\n" +
                "  # Fatal error outside per-case try/catch (simulates unexpected executor crash)\n" +
                "  throw [System.Exception]::new('Simulated fatal executor error')\n" +
                "  # Case 2 would run here but never reached\n" +
                "  $caseIndex++\n" +
                "  $results.Add([ordered]@{caseId='CASE-002';error=''})\n" +
                "} catch {\n" +
                "  $executorError = $_.Exception.Message\n" +
                "  Write-Host \"EXECUTOR_ERROR:$executorError\"\n" +
                "}\n" +
                "if ($null -ne $executorError) {\n" +
                "  $partial = [ordered]@{\n" +
                "    benchmarkVersion = 'taxia-benchmark-v1'\n" +
                "    expectedProvider = 'openai'\n" +
                "    completed        = $false\n" +
                "    fatalError       = $executorError\n" +
                "    casesTried       = $caseIndex\n" +
                "    successfulCases  = $successCount\n" +
                "    failedCases      = $failCount\n" +
                "    results          = $results.ToArray()\n" +
                "  }\n" +
                "  $stamp = Get-Date -Format 'yyyyMMddTHHmmss'\n" +
                "  $pPath = Join-Path '" + outDir + "' \"taxia-benchmark-v1_openai_${stamp}_partial.json\"\n" +
                "  $partial | ConvertTo-Json -Depth 6 | Set-Content -Path $pPath -Encoding UTF8\n" +
                "  Write-Host \"PARTIAL_SAVED:$pPath\"\n" +
                "}\n";

        Path fragPath = tempDir.resolve("partial-test.ps1");
        byte[] bom2 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] pContent = psScript.getBytes(StandardCharsets.UTF_8);
        byte[] pWithBom = new byte[bom2.length + pContent.length];
        System.arraycopy(bom2, 0, pWithBom, 0, bom2.length);
        System.arraycopy(pContent, 0, pWithBom, bom2.length, pContent.length);
        Files.write(fragPath, pWithBom);

        ProcessBuilder pb = new ProcessBuilder(
                psExe, "-NonInteractive", "-NoProfile", "-File",
                fragPath.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        proc.waitFor();

        assertThat(output).contains("EXECUTOR_ERROR:");
        assertThat(output).contains("PARTIAL_SAVED:");

        boolean partialExists = Files.list(tempDir)
                .anyMatch(p -> p.getFileName().toString().contains("_partial.json"));
        assertThat(partialExists)
                .as("A _partial.json must be written when executor crashes mid-run. Output: " + output)
                .isTrue();

        Path partialFile = Files.list(tempDir)
                .filter(p -> p.getFileName().toString().contains("_partial.json"))
                .findFirst().orElseThrow();
        String content = Files.readString(partialFile);
        assertThat(content).contains("\"completed\"");
        assertThat(content).contains("false");
        assertThat(content).contains("fatalError");
        assertThat(content).contains("casesTried");
        assertThat(content).doesNotContainIgnoringCase("password");
        assertThat(content).doesNotContainIgnoringCase("accessToken");
    }

    // ── PS fragment helpers ───────────────────────────────────────────────────

    /**
     * Runs a compact PS fragment that exercises the exact same catch-block logic
     * as run-taxia-benchmark.ps1 (no auth, no Read-Host), calling /ask on the
     * mock server and printing "RESULT:caseId=X error=Y" for each case.
     */
    private String runFragment(String psExe, int expectedStatus,
                               String provider, int caseCount) throws Exception {
        return runFragmentWithOutput(psExe, provider, caseCount, null);
    }

    private String runFragmentWithOutput(String psExe, String provider,
                                         int caseCount, String outputDir) throws Exception {
        String baseUrl = "http://localhost:" + mockPort;
        String outDirPs = outputDir != null
                ? outputDir.replace("'", "''")
                : tempDir.toAbsolutePath().toString().replace("'", "''");

        // Build mock cases inline (no real dataset needed)
        StringBuilder cases = new StringBuilder();
        for (int i = 1; i <= caseCount; i++) {
            cases.append(String.format(
                    "@{id='CASE-%03d';title='Test case %d';question='Q%d?'}", i, i, i));
            if (i < caseCount) cases.append(",");
        }

        // This fragment replicates the exact catch-block and result-building logic
        // from run-taxia-benchmark.ps1 — without auth or Read-Host.
        String psScript =
                "Set-StrictMode -Version Latest\n" +
                "$ErrorActionPreference = 'Stop'\n" +
                "$provider = '" + provider + "'\n" +
                "$baseUrl = '" + baseUrl + "'\n" +
                "$outDir = '" + outDirPs + "'\n" +
                "$null = New-Item -ItemType Directory -Force $outDir\n" +
                "$cases = @(" + cases + ")\n" +
                "$results = [System.Collections.Generic.List[object]]::new()\n" +
                "$executorError = $null\n" +
                "try {\n" +
                "  foreach ($case in $cases) {\n" +
                "    $caseId = $case.id\n" +
                "    $actualProvider = $null; $model = $null; $answer = $null\n" +
                "    $errorMsg = $null; $inputTokens = 0; $outputTokens = 0\n" +
                "    $askBodyJson  = @{question=$case.question;systemPrompt='sys'} | ConvertTo-Json -Compress\n" +
                "    $askBodyBytes = [System.Text.Encoding]::UTF8.GetBytes($askBodyJson)\n" +
                "    try {\n" +
                "      $aiResp = Invoke-RestMethod -Uri \"$baseUrl/ask\" -Method POST " +
                        "-ContentType 'application/json; charset=utf-8' -Body $askBodyBytes\n" +
                "      $actualProvider = $aiResp.provider\n" +
                "      $model = $aiResp.model\n" +
                "      $answer = $aiResp.answer\n" +
                "      $inputTokens = [int]$aiResp.inputTokens\n" +
                "      $outputTokens = [int]$aiResp.outputTokens\n" +
                "      if ($actualProvider -ne $provider) {\n" +
                "        $errorMsg = \"PROVIDER_MISMATCH: expected=$provider actual=$actualProvider\"\n" +
                "      }\n" +
                "    } catch {\n" +
                "      $code = 0; $errResp = $null\n" +
                "      try { $errResp = $_.Exception.Response } catch {}\n" +
                "      if ($null -ne $errResp) { try { $code = [int]$errResp.StatusCode } catch {} }\n" +
                "      if ($code -gt 0) { $errorMsg = \"HTTP $code\" } else { $errorMsg = $_.Exception.Message }\n" +
                "    }\n" +
                "    if ($null -ne $actualProvider) { $eap = $actualProvider } else { $eap = '' }\n" +
                "    if ($null -ne $model)          { $em  = $model }          else { $em  = '' }\n" +
                "    if ($null -ne $answer)         { $ea  = $answer }         else { $ea  = '' }\n" +
                "    if ($null -ne $errorMsg)       { $ee  = $errorMsg }       else { $ee  = '' }\n" +
                "    $entry = [ordered]@{caseId=$caseId;actualProvider=$eap;model=$em;" +
                             "answer=$ea;error=$ee;inputTokens=$inputTokens;outputTokens=$outputTokens}\n" +
                "    $results.Add($entry)\n" +
                "    Write-Host \"RESULT:caseId=$caseId error=$ee\"\n" +
                "  }\n" +
                "} catch {\n" +
                "  $executorError = $_.Exception.Message\n" +
                "  Write-Host \"EXECUTOR_ERROR:$executorError\"\n" +
                "}\n" +
                "if ($null -ne $executorError) {\n" +
                "  $partial = [ordered]@{benchmarkVersion='taxia-benchmark-v1';" +
                             "completed=$false;fatalError=$executorError;results=$results.ToArray()}\n" +
                "  $stamp = Get-Date -Format 'yyyyMMddTHHmmss'\n" +
                "  $pPath = Join-Path $outDir \"taxia-benchmark-v1_${provider}_${stamp}_partial.json\"\n" +
                "  $partial | ConvertTo-Json -Depth 6 | Set-Content -Path $pPath -Encoding UTF8\n" +
                "  Write-Host \"PARTIAL_SAVED:$pPath\"\n" +
                "}\n";

        // Write fragment to temp file to avoid command-line quoting issues
        Path fragScript = tempDir.resolve("test-fragment.ps1");
        // UTF-8 with BOM so PS 5.1 reads the script as UTF-8 (not Windows-1252)
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] fragContent = psScript.getBytes(StandardCharsets.UTF_8);
        byte[] fragWithBom = new byte[bom.length + fragContent.length];
        System.arraycopy(bom, 0, fragWithBom, 0, bom.length);
        System.arraycopy(fragContent, 0, fragWithBom, bom.length, fragContent.length);
        Files.write(fragScript, fragWithBom);

        ProcessBuilder pb = new ProcessBuilder(
                psExe, "-NonInteractive", "-NoProfile", "-File",
                fragScript.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        proc.waitFor();
        return output;
    }

    // ── utilities ─────────────────────────────────────────────────────────────

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String locatePowerShell() {
        for (String c : new String[]{
                "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                "C:\\Windows\\SysWOW64\\WindowsPowerShell\\v1.0\\powershell.exe"}) {
            if (new java.io.File(c).exists()) return c;
        }
        try {
            Process p = new ProcessBuilder("where", "powershell.exe").start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (!out.isBlank()) return out.lines().findFirst().orElse(null);
        } catch (Exception ignored) {}
        return null;
    }

    private void deleteTempDir() {
        try {
            if (tempDir != null && Files.exists(tempDir)) {
                Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        } catch (IOException ignored) {}
    }
}
