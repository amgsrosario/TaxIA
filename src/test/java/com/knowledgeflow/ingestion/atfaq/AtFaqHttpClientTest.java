package com.knowledgeflow.ingestion.atfaq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.ingestion.atfaq.AtFaqExceptions.FetchException;
import com.knowledgeflow.ingestion.atfaq.AtFaqExceptions.SecurityBlockedException;
import com.knowledgeflow.ingestion.atfaq.AtFaqExceptions.SourceBlockedException;
import com.knowledgeflow.ingestion.atfaq.AtFaqHttpClient.FetchResult;
import com.knowledgeflow.ingestion.atfaq.AtFaqHttpClient.Stats;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Local-server tests of the conservative HTTP client.
 * All traffic stays on localhost — zero external calls.
 */
class AtFaqHttpClientTest {

    private static HttpServer server;
    private static String base;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/ok", ex -> respond(ex, 200, "<html>olá conteúdo português</html>"));
        server.createContext("/forbidden", ex -> respond(ex, 403, ""));
        server.createContext("/rate-limited", ex -> respond(ex, 429, ""));
        server.createContext("/unavailable", ex -> respond(ex, 503, ""));
        server.createContext("/server-error", ex -> respond(ex, 500, ""));
        server.createContext("/etag", ex -> {
            String inm = ex.getRequestHeaders().getFirst("If-None-Match");
            if ("\"v1\"".equals(inm)) {
                ex.sendResponseHeaders(304, -1);
                ex.close();
            } else {
                ex.getResponseHeaders().add("ETag", "\"v1\"");
                respond(ex, 200, "<html>corpo</html>");
            }
        });
        server.createContext("/redirect-internal", ex -> {
            ex.getResponseHeaders().add("Location", base + "/ok");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/redirect-external", ex -> {
            ex.getResponseHeaders().add("Location", "https://evil.example.com/faqs.aspx");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/redirect-loop", ex -> {
            ex.getResponseHeaders().add("Location", base + "/redirect-loop");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/huge", ex -> respond(ex, 200, "x".repeat(5000)));
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "tarde demais");
        });
        server.start();
        base = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }

    private AtFaqProperties localProps() {
        AtFaqProperties props = new AtFaqProperties();
        props.setAllowedHosts(List.of("localhost"));
        props.setDelayMs(0);
        props.setMaxRetries(1);
        props.setConnectTimeout(Duration.ofSeconds(2));
        props.setReadTimeout(Duration.ofSeconds(5));
        return props;
    }

    private AtFaqHttpClient client(AtFaqProperties props) {
        return new AtFaqHttpClient(props, millis -> { /* no real sleeping in tests */ });
    }

    @Test
    @DisplayName("200 devolve corpo com encoding correcto e ETag")
    void fetchesOkPage() {
        Stats stats = new Stats();
        FetchResult result = client(localProps()).fetch(base + "/ok", null, null, stats);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).contains("olá conteúdo português");
        assertThat(stats.requests()).isEqualTo(1);
        assertThat(stats.statusCodes()).containsEntry(200, 1);
    }

    @Test
    @DisplayName("GET condicional: 304 devolve notModified sem corpo")
    void conditionalGetHonours304() {
        AtFaqHttpClient c = client(localProps());
        Stats stats = new Stats();
        FetchResult first = c.fetch(base + "/etag", null, null, stats);
        assertThat(first.etag()).isEqualTo("\"v1\"");

        FetchResult second = c.fetch(base + "/etag", first.etag(), null, stats);
        assertThat(second.notModified()).isTrue();
        assertThat(second.body()).isNull();
    }

    @Test
    @DisplayName("403, 429 e 503 param imediatamente o run (sem retry)")
    void blockingStatusesAbortWithoutRetry() {
        AtFaqHttpClient c = client(localProps());
        for (String path : List.of("/forbidden", "/rate-limited", "/unavailable")) {
            Stats stats = new Stats();
            assertThatThrownBy(() -> c.fetch(base + path, null, null, stats))
                    .isInstanceOf(SourceBlockedException.class);
            assertThat(stats.retries()).as("retries em %s", path).isZero();
            assertThat(stats.requests()).as("pedidos em %s", path).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("500 falha o item sem bloquear nem fazer retry de HTTP")
    void serverErrorFailsItem() {
        Stats stats = new Stats();
        assertThatThrownBy(() -> client(localProps()).fetch(base + "/server-error", null, null, stats))
                .isInstanceOf(FetchException.class);
    }

    @Test
    @DisplayName("Redirect interno (mesmo host) é seguido")
    void followsInternalRedirect() {
        Stats stats = new Stats();
        FetchResult result = client(localProps()).fetch(base + "/redirect-internal", null, null, stats);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.finalUrl()).endsWith("/ok");
    }

    @Test
    @DisplayName("Redirect para host externo é bloqueado (SSRF)")
    void blocksExternalRedirect() {
        Stats stats = new Stats();
        assertThatThrownBy(() -> client(localProps()).fetch(base + "/redirect-external", null, null, stats))
                .isInstanceOf(SecurityBlockedException.class)
                .hasMessageContaining("evil.example.com");
    }

    @Test
    @DisplayName("Loop de redirects é cortado pelo limite")
    void breaksRedirectLoops() {
        Stats stats = new Stats();
        assertThatThrownBy(() -> client(localProps()).fetch(base + "/redirect-loop", null, null, stats))
                .isInstanceOf(FetchException.class)
                .hasMessageContaining("redirects");
    }

    @Test
    @DisplayName("Host fora da allowlist é recusado antes de qualquer pedido")
    void refusesNonAllowlistedHost() {
        Stats stats = new Stats();
        assertThatThrownBy(() -> client(localProps())
                .fetch("http://127.0.0.1:" + server.getAddress().getPort() + "/ok", null, null, stats))
                .isInstanceOf(SecurityBlockedException.class);
        assertThat(stats.requests()).isZero();
    }

    @Test
    @DisplayName("URLs inválidas e esquemas não suportados são recusados")
    void refusesInvalidUrls() {
        AtFaqHttpClient c = client(localProps());
        Stats stats = new Stats();
        assertThatThrownBy(() -> c.fetch("ftp://localhost/x", null, null, stats))
                .isInstanceOf(SecurityBlockedException.class);
        assertThatThrownBy(() -> c.fetch("http://user:pass@localhost/x", null, null, stats))
                .isInstanceOf(SecurityBlockedException.class);
        assertThatThrownBy(() -> c.fetch("ht tp://inválido", null, null, stats))
                .isInstanceOf(SecurityBlockedException.class);
    }

    @Test
    @DisplayName("Resposta acima do limite de tamanho é rejeitada")
    void rejectsOversizedResponse() {
        AtFaqProperties props = localProps();
        props.setMaxResponseBytes(1000);
        Stats stats = new Stats();
        assertThatThrownBy(() -> client(props).fetch(base + "/huge", null, null, stats))
                .isInstanceOf(FetchException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("Timeout de leitura falha com retry limitado a falhas transitórias")
    void readTimeoutIsRetriedThenFails() {
        AtFaqProperties props = localProps();
        props.setReadTimeout(Duration.ofMillis(300));
        props.setMaxRetries(1);
        Stats stats = new Stats();
        assertThatThrownBy(() -> client(props).fetch(base + "/slow", null, null, stats))
                .isInstanceOf(FetchException.class);
        assertThat(stats.retries()).isEqualTo(1);
    }

    @Test
    @DisplayName("Falha de ligação é retried o número configurado de vezes")
    void connectionFailureIsRetried() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        AtFaqProperties props = localProps();
        props.setMaxRetries(2);
        Stats stats = new Stats();
        assertThatThrownBy(() -> client(props)
                .fetch("http://localhost:" + freePort + "/ok", null, null, stats))
                .isInstanceOf(FetchException.class);
        assertThat(stats.retries()).isEqualTo(2);
    }
}
