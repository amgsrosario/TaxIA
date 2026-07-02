package com.knowledgeflow.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end encoding round-trip: mock HTTP server (UTF-8 response, no charset in Content-Type)
 * → PowerShell 5.1 Invoke-JsonPostUtf8 → JSON file → CSV file → Java re-read.
 *
 * Validates that:
 * - Portuguese text is decoded correctly from UTF-8 bytes (no mojibake)
 * - JSON output is UTF-8 without BOM
 * - CSV output is UTF-8 with BOM (for Excel on Windows)
 * - Known mojibake patterns are absent
 * - € and .º survive round-trip intact
 *
 * Static tests always run. PS-execution tests require powershell.exe.
 * No calls to Anthropic or OpenAI are made.
 */
class BenchmarkEncodingRoundTripTest {

    // Portuguese text covering all problematic characters from production mojibake
    private static final String PORTUGUESE_ANSWER =
            "Código do IVA português, operações tributáveis, " +
            "isenção médica, dedução do imposto, " +
            "Autoridade Tributária, 10 000 €, artigo 9.º";

    // Mojibake patterns produced when UTF-8 is read as Windows-1252
    private static final List<String> MOJIBAKE_PATTERNS = List.of(
            "CÃ³",   // ó → Ã³
            "Ã§",    // ç → Ã§
            "Ã£",    // ã → Ã£
            "Âº",    // º → Âº
            "â¬" // € → â‚¬ (multi-byte sequence)
    );

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer mockServer;
    private int mockPort;
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockPort = mockServer.getAddress().getPort();
        tempDir = Files.createTempDirectory("taxia-enc-rt-");
    }

    @AfterEach
    void tearDown() {
        if (mockServer != null) mockServer.stop(0);
        deleteTempDir();
    }

    // ── static / unit tests — always run ─────────────────────────────────────

    @Test
    void mojibakePatternsListCoversKnownProblematicChars() {
        // Sanity-check: confirmed mojibake from production run
        String production = "CÃ³digo do IVA portuguÃªs, operaÃ§Ãµes";
        boolean hasMojibake = MOJIBAKE_PATTERNS.stream().anyMatch(production::contains);
        assertThat(hasMojibake).as("Production string must contain known mojibake patterns").isTrue();
    }

    @Test
    void cleanPortugueseAnswerContainsNoMojibakePatterns() {
        for (String pattern : MOJIBAKE_PATTERNS) {
            assertThat(PORTUGUESE_ANSWER)
                    .as("Clean Portuguese answer must not contain mojibake pattern: %s", pattern)
                    .doesNotContain(pattern);
        }
    }

    @Test
    void cleanPortugueseAnswerContainsExpectedChars() {
        assertThat(PORTUGUESE_ANSWER).contains("ó");   // ó
        assertThat(PORTUGUESE_ANSWER).contains("ç");   // ç
        assertThat(PORTUGUESE_ANSWER).contains("ã");   // ã
        assertThat(PORTUGUESE_ANSWER).contains("€");   // €
        assertThat(PORTUGUESE_ANSWER).contains("º");   // º
    }

    // ── PS round-trip tests — require powershell.exe ─────────────────────────

    @Test
    void responseIsDecodedWithoutMojibakeByInvokeJsonPostUtf8() throws Exception {
        String psExe = locatePowerShell();
        org.junit.jupiter.api.Assumptions.assumeTrue(psExe != null, "powershell.exe not found");

        mockServer.createContext("/ask", ex -> {
            // Server sends UTF-8 bytes with Content-Type: application/json (no charset)
            // This simulates Spring Boot's default behaviour
            respond(ex, 200, buildAskResponse(PORTUGUESE_ANSWER));
        });
        mockServer.start();

        RoundTripFiles files = runRoundTripFragment(psExe);

        // JSON file: read as UTF-8, validate no mojibake
        byte[] jsonBytes = Files.readAllBytes(files.json);
        String jsonContent = new String(jsonBytes, StandardCharsets.UTF_8);

        assertNoMojibake(jsonContent, "JSON");
        assertThat(jsonContent).contains("ó");  // ó preserved
        assertThat(jsonContent).contains("€");  // € preserved
        assertThat(jsonContent).contains("º");  // º preserved
    }

    @Test
    void jsonOutputFileIsUtf8WithoutBom() throws Exception {
        String psExe = locatePowerShell();
        org.junit.jupiter.api.Assumptions.assumeTrue(psExe != null, "powershell.exe not found");

        mockServer.createContext("/ask", ex -> respond(ex, 200, buildAskResponse(PORTUGUESE_ANSWER)));
        mockServer.start();

        RoundTripFiles files = runRoundTripFragment(psExe);

        byte[] jsonBytes = Files.readAllBytes(files.json);
        assertThat(jsonBytes).as("JSON must not be empty").isNotEmpty();

        // Must NOT start with UTF-8 BOM (EF BB BF)
        assertThat(jsonBytes[0] & 0xFF).as("JSON first byte must not be BOM (0xEF)").isNotEqualTo(0xEF);

        // Must be valid JSON parseable by Jackson
        assertThat(jsonBytes[0] & 0xFF)
                .as("JSON must start with '{' or '[' (0x7B or 0x5B)")
                .isIn(0x7B, 0x5B);

        String decoded = new String(jsonBytes, StandardCharsets.UTF_8);
        JsonNode node = MAPPER.readTree(decoded);
        assertThat(node).isNotNull();
    }

    @Test
    void csvOutputFileIsUtf8WithBom() throws Exception {
        String psExe = locatePowerShell();
        org.junit.jupiter.api.Assumptions.assumeTrue(psExe != null, "powershell.exe not found");

        mockServer.createContext("/ask", ex -> respond(ex, 200, buildAskResponse(PORTUGUESE_ANSWER)));
        mockServer.start();

        RoundTripFiles files = runRoundTripFragment(psExe);

        byte[] csvBytes = Files.readAllBytes(files.csv);
        assertThat(csvBytes).as("CSV must not be empty").isNotEmpty();
        assertThat(csvBytes.length).isGreaterThan(3);

        // Must start with UTF-8 BOM (EF BB BF) — required for Excel on Windows
        assertThat(csvBytes[0] & 0xFF).as("CSV BOM byte 0 must be 0xEF").isEqualTo(0xEF);
        assertThat(csvBytes[1] & 0xFF).as("CSV BOM byte 1 must be 0xBB").isEqualTo(0xBB);
        assertThat(csvBytes[2] & 0xFF).as("CSV BOM byte 2 must be 0xBF").isEqualTo(0xBF);

        // Content after BOM must be valid UTF-8 without mojibake
        String csvContent = new String(csvBytes, 3, csvBytes.length - 3, StandardCharsets.UTF_8);
        assertNoMojibake(csvContent, "CSV");
        assertThat(csvContent).contains("ó");  // ó
        assertThat(csvContent).contains("€");  // €
        assertThat(csvContent).contains("º");  // º
    }

    @Test
    void jsonAnswerRoundTripPreservesFullPortugueseText() throws Exception {
        String psExe = locatePowerShell();
        org.junit.jupiter.api.Assumptions.assumeTrue(psExe != null, "powershell.exe not found");

        mockServer.createContext("/ask", ex -> respond(ex, 200, buildAskResponse(PORTUGUESE_ANSWER)));
        mockServer.start();

        RoundTripFiles files = runRoundTripFragment(psExe);

        String jsonContent = Files.readString(files.json, StandardCharsets.UTF_8);
        JsonNode root = MAPPER.readTree(jsonContent);
        String answer = root.path("answer").asText();

        assertThat(answer).as("Round-trip answer must not be empty").isNotEmpty();
        assertNoMojibake(answer, "answer field");

        // Verify key Portuguese characters survived intact
        assertThat(answer).contains("Código");         // Código
        assertThat(answer).contains("português");      // português
        assertThat(answer).contains("operações"); // operações
        assertThat(answer).contains("isenção");   // isenção
        assertThat(answer).contains("€");               // €
        assertThat(answer).contains("9.º");             // 9.º
    }

    @Test
    void csvAnswerRoundTripPreservesPortugueseText() throws Exception {
        String psExe = locatePowerShell();
        org.junit.jupiter.api.Assumptions.assumeTrue(psExe != null, "powershell.exe not found");

        mockServer.createContext("/ask", ex -> respond(ex, 200, buildAskResponse(PORTUGUESE_ANSWER)));
        mockServer.start();

        RoundTripFiles files = runRoundTripFragment(psExe);

        byte[] csvBytes = Files.readAllBytes(files.csv);
        // Skip BOM if present
        int offset = (csvBytes.length > 3 && (csvBytes[0] & 0xFF) == 0xEF) ? 3 : 0;
        String csvContent = new String(csvBytes, offset, csvBytes.length - offset, StandardCharsets.UTF_8);

        assertNoMojibake(csvContent, "CSV content");
        assertThat(csvContent).contains("ó");   // ó
        assertThat(csvContent).contains("ç");   // ç
        assertThat(csvContent).contains("€");   // €
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private RoundTripFiles runRoundTripFragment(String psExe) throws Exception {
        Path jsonPath = tempDir.resolve("result.json");
        Path csvPath  = tempDir.resolve("result.csv");
        String outDir = tempDir.toAbsolutePath().toString().replace("'", "''");
        String jsonPathEsc = jsonPath.toAbsolutePath().toString().replace("'", "''");
        String csvPathEsc  = csvPath.toAbsolutePath().toString().replace("'", "''");

        // The fragment inlines Invoke-JsonPostUtf8 — same implementation as run-taxia-benchmark.ps1
        String psScript =
                "Set-StrictMode -Version Latest\n" +
                "$ErrorActionPreference = 'Stop'\n" +
                "function Invoke-JsonPostUtf8 {\n" +
                "    param([string]$Uri,[byte[]]$BodyBytes,[string]$Authorization='',[int]$TimeoutSec=120)\n" +
                "    $req = [System.Net.HttpWebRequest]::Create($Uri)\n" +
                "    $req.Method = 'POST'\n" +
                "    $req.ContentType = 'application/json; charset=utf-8'\n" +
                "    $req.Timeout = $TimeoutSec * 1000\n" +
                "    $req.ContentLength = $BodyBytes.Length\n" +
                "    if ($Authorization -ne '') { $req.Headers.Add('Authorization', $Authorization) }\n" +
                "    $reqStream = $req.GetRequestStream()\n" +
                "    $reqStream.Write($BodyBytes, 0, $BodyBytes.Length)\n" +
                "    $reqStream.Close()\n" +
                "    $resp = $req.GetResponse()\n" +
                "    $stream = $resp.GetResponseStream()\n" +
                "    $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)\n" +
                "    $jsonString = $reader.ReadToEnd()\n" +
                "    $reader.Dispose()\n" +
                "    $resp.Close()\n" +
                "    return $jsonString | ConvertFrom-Json\n" +
                "}\n" +
                "$bodyJson  = '{\"question\":\"Definicao fiscal\",\"systemPrompt\":\"sys\"}'\n" +
                "$bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($bodyJson)\n" +
                "$aiResp = Invoke-JsonPostUtf8 -Uri 'http://localhost:" + mockPort + "/ask'" +
                        " -BodyBytes $bodyBytes\n" +
                "$null = New-Item -ItemType Directory -Force '" + outDir + "'\n" +
                // Build result
                "$result = [ordered]@{\n" +
                "    benchmarkVersion = 'taxia-benchmark-v1'\n" +
                "    completed = $true\n" +
                "    expectedProvider = 'openai'\n" +
                "    answer = $aiResp.answer\n" +
                "    results = @(\n" +
                "        [ordered]@{ caseId = 'TAXIA-001'; answer = $aiResp.answer }\n" +
                "    )\n" +
                "}\n" +
                // JSON: UTF-8 without BOM
                "$jsonString = $result | ConvertTo-Json -Depth 6\n" +
                "[System.IO.File]::WriteAllText('" + jsonPathEsc + "', $jsonString," +
                        " [System.Text.UTF8Encoding]::new($false))\n" +
                // CSV: UTF-8 with BOM
                "$csvLines = [System.Collections.Generic.List[string]]::new()\n" +
                "$csvLines.Add('benchmark_version,case_id,answer')\n" +
                "$escapedAnswer = $aiResp.answer -replace '\"', '\"\"'\n" +
                "$csvLines.Add(\"taxia-benchmark-v1,TAXIA-001,`\"$escapedAnswer`\"\")\n" +
                "[System.IO.File]::WriteAllLines('" + csvPathEsc + "', $csvLines," +
                        " [System.Text.UTF8Encoding]::new($true))\n" +
                "Write-Host 'DONE'\n";

        runPs(psExe, "round-trip.ps1", psScript);

        assertThat(jsonPath.toFile()).as("JSON file must exist after PS run").exists();
        assertThat(csvPath.toFile()).as("CSV file must exist after PS run").exists();
        return new RoundTripFiles(jsonPath, csvPath);
    }

    private void assertNoMojibake(String text, String context) {
        for (String pattern : MOJIBAKE_PATTERNS) {
            assertThat(text)
                    .as("%s must not contain mojibake pattern '%s'", context, pattern)
                    .doesNotContain(pattern);
        }
    }

    private static String buildAskResponse(String answer) {
        // Escape the answer for JSON embedding
        String escaped = answer
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        return "{\"answer\":\"" + escaped + "\",\"provider\":\"openai\"," +
               "\"model\":\"gpt-test\",\"inputTokens\":50,\"outputTokens\":80}";
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        // Deliberately omit charset from Content-Type to reproduce Spring Boot default behaviour
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void runPs(String psExe, String fileName, String script) throws Exception {
        Path fragPath = tempDir.resolve(fileName);
        // UTF-8 with BOM so PS 5.1 reads the script file as UTF-8 (not Windows-1252)
        byte[] bom     = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] content = script.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + content.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(content, 0, withBom, bom.length, content.length);
        Files.write(fragPath, withBom);
        ProcessBuilder pb = new ProcessBuilder(
                psExe, "-NonInteractive", "-NoProfile", "-File",
                fragPath.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        proc.getInputStream().readAllBytes();
        proc.waitFor();
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

    private record RoundTripFiles(Path json, Path csv) {}
}
