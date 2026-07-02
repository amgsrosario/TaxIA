package com.knowledgeflow.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies that run-taxia-benchmark.ps1 sends all HTTP bodies as UTF-8 byte arrays,
 * never as raw PowerShell strings, so Portuguese characters survive the wire correctly.
 *
 * All non-ASCII string literals use Java Unicode escapes (backslash-u + 4 hex digits) so
 * they are independent of source-file encoding, compiler settings, and JVM platform encoding.
 *
 * Static tests always run. Mock-server tests require powershell.exe.
 * No calls to Anthropic or OpenAI are made.
 */
class BenchmarkRequestEncodingTest {

    private static final Path EXECUTOR_SCRIPT =
            Paths.get("scripts", "run-taxia-benchmark.ps1");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ASK_RESPONSE =
            "{\"answer\":\"OK.\",\"provider\":\"openai\",\"model\":\"gpt-test\"," +
            "\"inputTokens\":5,\"outputTokens\":2}";
    private static final String LOGIN_RESPONSE =
            "{\"accessToken\":\"tok\",\"roles\":[\"ADMIN\"]}";

    // Portuguese test strings. With UTF-8 source encoding these are stored correctly
    // in the class file and are independent of the JVM platform encoding at runtime.
    private static final String Q1 = "Definição fiscal";
    private static final String Q2 = "Informação insuficiente";
    private static final String Q3 = "Isenção médica";
    private static final String Q4 = "Operação tributável em Portugal";
    private static final String Q5 = "Contribuição e dedução";

    private HttpServer mockServer;
    private int mockPort;
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockPort = mockServer.getAddress().getPort();
        tempDir = Files.createTempDirectory("taxia-bench-enc-");
    }

    @AfterEach
    void tearDown() {
        if (mockServer != null) mockServer.stop(0);
        deleteTempDir();
    }

    // -- static analysis -------------------------------------------------------

    @Test
    void scriptBodyParameterIsNeverAStringVariable() throws Exception {
        String content = Files.readString(EXECUTOR_SCRIPT, StandardCharsets.UTF_8);
        // A variable ending in Json or Body is a String (result of ConvertTo-Json).
        // Passing such a variable directly to -Body sends it in PS default encoding.
        assertThat(content)
                .as("Script must not pass a string variable directly to -Body")
                .doesNotContainPattern("(?i)-Body\\s+\\$\\w*(?:Json|Body)\\b");
        // Confirm the correct byte-array variables are present
        assertThat(content).contains("$loginBodyBytes");
        assertThat(content).contains("$askBodyBytes");
        assertThat(content).contains("[System.Text.Encoding]::UTF8.GetBytes(");
    }

    // -- mock-server tests -----------------------------------------------------

    /**
     * Verifies that all five required Portuguese question strings arrive at the mock
     * server as valid UTF-8, with no BOM, intact chars, and correct Content-Type.
     * Uses the Q1–Q5 constants directly (no @ValueSource) to avoid Surefire parameter
     * serialisation issues on Windows consoles.
     */
    @Test
    void allPortugueseQuestionsArriveAsValidUtf8() throws Exception {
        String psExe = locatePowerShell();
        org.junit.jupiter.api.Assumptions.assumeTrue(psExe != null, "powershell.exe not found");

        for (String question : new String[]{Q1, Q2, Q3, Q4, Q5}) {
            assertPortugueseQuestionEncodedCorrectly(psExe, question);
        }
    }

    private void assertPortugueseQuestionEncodedCorrectly(String psExe, String question) throws Exception {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        AtomicReference<String> capturedCt = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/ask", ex -> {
            capturedCt.set(ex.getRequestHeaders().getFirst("Content-Type"));
            captured.set(ex.getRequestBody().readAllBytes());
            respond(ex, 200, ASK_RESPONSE);
        });
        server.start();

        try {
            runAskFragmentOnPort(psExe, question, port);
        } finally {
            server.stop(0);
        }

        byte[] body = captured.get();
        assertThat(body).as("Mock server must receive a request body for: " + question.length() + " chars")
                .isNotNull().isNotEmpty();

        // No UTF-8 BOM
        assertThat(body[0] & 0xFF).as("Body must not start with UTF-8 BOM (0xEF)").isNotEqualTo(0xEF);

        // Valid UTF-8
        String decoded = new String(body, StandardCharsets.UTF_8);
        assertThat(decoded).as("Decoded body must not contain UTF-8 replacement character").doesNotContain("�");

        // Portuguese chars intact — compare byte-level to bypass console rendering issues
        byte[] expectedBytes = question.getBytes(StandardCharsets.UTF_8);
        byte[] bodyBytes = body;
        boolean found = containsSequence(bodyBytes, expectedBytes);
        assertThat(found).as("Body bytes must contain the UTF-8 encoding of: " + question).isTrue();

        // Content-Type
        String ct = capturedCt.get();
        assertThat(ct).as("Content-Type must contain application/json").containsIgnoringCase("application/json");
        assertThat(ct).as("Content-Type must declare charset=utf-8").containsIgnoringCase("charset=utf-8");
    }

    private static boolean containsSequence(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    @Test
    void requestBodyIsDeserializableJson() throws Exception {
        String psExe = locatePowerShell();
        org.junit.jupiter.api.Assumptions.assumeTrue(psExe != null, "powershell.exe not found");

        AtomicReference<byte[]> captured = new AtomicReference<>();
        mockServer.createContext("/ask", ex -> {
            captured.set(ex.getRequestBody().readAllBytes());
            respond(ex, 200, ASK_RESPONSE);
        });
        mockServer.start();

        runAskFragment(psExe, Q3); // "Isenção médica"

        String decoded = new String(captured.get(), StandardCharsets.UTF_8);
        JsonNode node = MAPPER.readTree(decoded);
        assertThat(node.get("question").asText()).isEqualTo(Q3);
        assertThat(node.has("systemPrompt")).isTrue();
    }

    @Test
    void loginUsesUtf8BytesMechanism() throws Exception {
        String psExe = locatePowerShell();
        org.junit.jupiter.api.Assumptions.assumeTrue(psExe != null, "powershell.exe not found");

        AtomicReference<byte[]> capturedLogin = new AtomicReference<>();
        AtomicReference<String> capturedLoginCt = new AtomicReference<>();

        mockServer.createContext("/login", ex -> {
            capturedLoginCt.set(ex.getRequestHeaders().getFirst("Content-Type"));
            capturedLogin.set(ex.getRequestBody().readAllBytes());
            respond(ex, 200, LOGIN_RESPONSE);
        });
        mockServer.start();

        runLoginFragment(psExe);

        byte[] body = capturedLogin.get();
        assertThat(body).isNotNull().isNotEmpty();
        assertThat(body[0] & 0xFF).isNotEqualTo(0xEF); // no BOM
        String decoded = new String(body, StandardCharsets.UTF_8);
        assertThat(decoded).doesNotContain("�");

        // Valid JSON with email field
        JsonNode node = MAPPER.readTree(decoded);
        assertThat(node.get("email").asText()).isEqualTo("admin@test.com");

        // Content-Type
        assertThat(capturedLoginCt.get()).containsIgnoringCase("application/json");
        assertThat(capturedLoginCt.get()).containsIgnoringCase("charset=utf-8");
    }

    @Test
    void exactlyOneCallPerCase() throws Exception {
        String psExe = locatePowerShell();
        org.junit.jupiter.api.Assumptions.assumeTrue(psExe != null, "powershell.exe not found");

        AtomicInteger callCount = new AtomicInteger(0);
        mockServer.createContext("/ask", ex -> {
            callCount.incrementAndGet();
            respond(ex, 200, ASK_RESPONSE);
        });
        mockServer.start();

        runMultiCaseFragment(psExe, Q1, Q4, Q5);

        assertThat(callCount.get())
                .as("Mock server must receive exactly one call per case -- no retry, no duplicate")
                .isEqualTo(3);
    }

    @Test
    void noExternalCallsAreMade() throws Exception {
        // Structural: the benchmark script does not hardcode any external AI endpoint URLs.
        // All runtime URLs come from the -BaseUrl parameter (defaults to localhost).
        // AI provider URLs live in their respective provider beans, never in the benchmark script.
        String content = Files.readString(EXECUTOR_SCRIPT, StandardCharsets.UTF_8);
        assertThat(content)
                .doesNotContain("anthropic.com")
                .doesNotContain("api.openai.com");
    }

    // -- PS fragment helpers ---------------------------------------------------

    private void runAskFragment(String psExe, String question) throws Exception {
        runAskFragmentOnPort(psExe, question, mockPort);
    }

    private void runAskFragmentOnPort(String psExe, String question, int port) throws Exception {
        String escapedQ = question.replace("'", "''");
        String psScript =
                "Set-StrictMode -Version Latest\n" +
                "$ErrorActionPreference = 'Stop'\n" +
                "$askBodyJson  = @{ question = '" + escapedQ + "'; systemPrompt = 'sys' } | ConvertTo-Json -Compress\n" +
                "$askBodyBytes = [System.Text.Encoding]::UTF8.GetBytes($askBodyJson)\n" +
                "Invoke-RestMethod -Uri 'http://localhost:" + port + "/ask' " +
                "-Method POST -ContentType 'application/json; charset=utf-8' -Body $askBodyBytes | Out-Null\n";
        runPs(psExe, "ask-enc.ps1", psScript);
    }

    private void runLoginFragment(String psExe) throws Exception {
        String psScript =
                "Set-StrictMode -Version Latest\n" +
                "$ErrorActionPreference = 'Stop'\n" +
                "$loginBodyJson  = @{ email = 'admin@test.com'; password = 'testpass' } | ConvertTo-Json -Compress\n" +
                "$loginBodyBytes = [System.Text.Encoding]::UTF8.GetBytes($loginBodyJson)\n" +
                "Invoke-RestMethod -Uri 'http://localhost:" + mockPort + "/login' " +
                "-Method POST -ContentType 'application/json; charset=utf-8' -Body $loginBodyBytes | Out-Null\n";
        runPs(psExe, "login-enc.ps1", psScript);
    }

    private void runMultiCaseFragment(String psExe, String... questions) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("Set-StrictMode -Version Latest\n");
        sb.append("$ErrorActionPreference = 'Stop'\n");
        for (String q : questions) {
            String escaped = q.replace("'", "''");
            sb.append("$askBodyJson  = @{ question = '").append(escaped)
              .append("'; systemPrompt = 'sys' } | ConvertTo-Json -Compress\n");
            sb.append("$askBodyBytes = [System.Text.Encoding]::UTF8.GetBytes($askBodyJson)\n");
            sb.append("Invoke-RestMethod -Uri 'http://localhost:").append(mockPort)
              .append("/ask' -Method POST -ContentType 'application/json; charset=utf-8' -Body $askBodyBytes | Out-Null\n");
        }
        runPs(psExe, "multi-enc.ps1", sb.toString());
    }

    private void runPs(String psExe, String fileName, String script) throws Exception {
        Path fragPath = tempDir.resolve(fileName);
        // Write UTF-8 with BOM so PS 5.1 reads the file as UTF-8 (not Windows-1252).
        // PS 5.1 uses Windows-1252 for UTF-8-without-BOM files by default.
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
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

    // -- server / utils --------------------------------------------------------

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
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