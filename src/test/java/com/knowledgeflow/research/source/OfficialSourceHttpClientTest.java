package com.knowledgeflow.research.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.research.source.OfficialSourceClient.FetchedPage;
import com.knowledgeflow.research.source.OfficialSourceClient.Outcome;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Garantias do cliente de fontes oficiais: SSRF, só HTTPS e leitura do corpo com limite (CR-01/CR-02).
 * Os testes de rede usam um servidor HTTP local, através do construtor de pacote que admite
 * {@code http}; o construtor público (produção) só admite HTTPS.
 */
class OfficialSourceHttpClientTest {

    @Nested
    class UrlValidation {

        private final OfficialSourceHttpClient client = new OfficialSourceHttpClient(new ResearchProperties());

        @Test
        @DisplayName("Produção: só HTTPS, só hosts da allowlist, sem credenciais no URL")
        void httpsOnlyAllowlist() {
            assertThat(client.validateUrl("https://info.portaldasfinancas.gov.pt/pt/x.aspx").getHost())
                    .isEqualTo("info.portaldasfinancas.gov.pt");
            for (String url : List.of(
                    "http://info.portaldasfinancas.gov.pt/pt/x.aspx",
                    "HTTP://info.portaldasfinancas.gov.pt/pt/x.aspx",
                    "https://example.com/",
                    "https://info.portaldasfinancas.gov.pt.evil.test/",
                    "ftp://info.portaldasfinancas.gov.pt/",
                    "https://user:pw@info.portaldasfinancas.gov.pt/",
                    "https://www.portaldasfinancas.gov.pt/")) {
                assertThatThrownBy(() -> client.validateUrl(url)).as(url).isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("Produção: URL http ou fora da allowlist é BLOCKED sem pedido de rede")
        void blockedWithoutNetwork() {
            assertThat(client.fetch("http://info.portaldasfinancas.gov.pt/pt/x.aspx").outcome()).isEqualTo(Outcome.BLOCKED);
            assertThat(client.fetch("https://example.com/artigo.aspx").outcome()).isEqualTo(Outcome.BLOCKED);
        }
    }

    @Nested
    class ReadLimited {

        private byte[] bytes(int n) {
            byte[] b = new byte[n];
            Arrays.fill(b, (byte) 'a');
            return b;
        }

        @Test
        @DisplayName("Abaixo e igual ao limite: lê tudo; acima: interrompe com erro")
        void belowEqualAbove() throws IOException {
            assertThat(OfficialSourceHttpClient.readLimited(new ByteArrayInputStream(bytes(999)), 1000)).hasSize(999);
            assertThat(OfficialSourceHttpClient.readLimited(new ByteArrayInputStream(bytes(1000)), 1000)).hasSize(1000);
            assertThatThrownBy(() -> OfficialSourceHttpClient.readLimited(new ByteArrayInputStream(bytes(1001)), 1000))
                    .isInstanceOf(OfficialSourceHttpClient.ResponseTooLargeException.class);
        }

        @Test
        @DisplayName("Nunca lê mais do que limite + 1 bytes da fonte")
        void readsAtMostLimitPlusOne() {
            var source = new ByteArrayInputStream(bytes(10_000_000));
            assertThatThrownBy(() -> OfficialSourceHttpClient.readLimited(source, 1000))
                    .isInstanceOf(OfficialSourceHttpClient.ResponseTooLargeException.class);
            assertThat(10_000_000 - source.available()).isEqualTo(1001);
        }
    }

    @Nested
    class LocalServer {

        static final int LIMIT = 4096;
        static final long HUGE = 64L * 1024 * 1024;
        /**
         * Tecto do que o servidor chega a escrever antes de a ligação ser cortada (buffers TCP).
         * Medido em 2026-10-22: 0,3–1,1 MB de 64 MB.
         */
        static final long CUT_BOUND = 8L * 1024 * 1024;

        HttpServer server;
        OfficialSourceHttpClient client;
        String base;
        final AtomicLong written = new AtomicLong();
        final CountDownLatch handlerDone = new CountDownLatch(1);

        @BeforeEach
        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/below", ex -> respond(ex, 200, LIMIT - 1, true));
            server.createContext("/equal", ex -> respond(ex, 200, LIMIT, true));
            server.createContext("/above-declared", ex -> respond(ex, 200, LIMIT + 1, true));
            server.createContext("/huge-declared", ex -> respond(ex, 200, HUGE, true));
            server.createContext("/huge-chunked", ex -> respond(ex, 200, HUGE, false));
            server.createContext("/not-found-huge", ex -> respond(ex, 404, HUGE, false));
            server.createContext("/redirect-ok", ex -> redirect(ex, "/below"));
            server.createContext("/redirect-huge", ex -> redirect(ex, "/huge-chunked"));
            server.createContext("/redirect-https", ex -> redirect(ex, "https://127.0.0.1/below"));
            server.createContext("/redirect-external", ex -> redirect(ex, "http://example.com/below"));
            server.start();
            base = "http://127.0.0.1:" + server.getAddress().getPort();

            ResearchProperties props = new ResearchProperties();
            props.setAllowedHosts(List.of("127.0.0.1"));
            props.setMaxResponseBytes(LIMIT);
            props.setDelayMs(0);
            props.setReadTimeout(Duration.ofSeconds(10));
            client = new OfficialSourceHttpClient(props, Set.of("http"));
        }

        @AfterEach
        void stop() {
            server.stop(0);
        }

        /** Escreve {@code size} bytes em blocos; regista quanto chegou a escrever antes de o cliente fechar. */
        private void respond(HttpExchange ex, int status, long size, boolean declareLength) throws IOException {
            try {
                ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                ex.sendResponseHeaders(status, declareLength ? size : 0);
                byte[] chunk = new byte[16 * 1024];
                Arrays.fill(chunk, (byte) 'x');
                try (OutputStream out = ex.getResponseBody()) {
                    long left = size;
                    while (left > 0) {
                        int n = (int) Math.min(chunk.length, left);
                        out.write(chunk, 0, n);
                        written.addAndGet(n);
                        left -= n;
                    }
                }
            } catch (IOException clientClosed) {
                // o cliente interrompeu a leitura: é o comportamento esperado
            } finally {
                handlerDone.countDown();
            }
        }

        private void redirect(HttpExchange ex, String location) throws IOException {
            ex.getResponseHeaders().add("Location", location);
            ex.sendResponseHeaders(302, -1);
            ex.close();
        }

        private long writtenWhenHandlerDone() throws InterruptedException {
            assertThat(handlerDone.await(30, TimeUnit.SECONDS)).as("handler terminou").isTrue();
            return written.get();
        }

        @Test
        @DisplayName("Abaixo do limite → FETCHED com o corpo completo")
        void below() {
            FetchedPage page = client.fetch(base + "/below");
            assertThat(page.outcome()).isEqualTo(Outcome.FETCHED);
            assertThat(page.body()).hasSize(LIMIT - 1);
        }

        @Test
        @DisplayName("Igual ao limite → FETCHED com o corpo completo")
        void equal() {
            FetchedPage page = client.fetch(base + "/equal");
            assertThat(page.outcome()).isEqualTo(Outcome.FETCHED);
            assertThat(page.body()).hasSize(LIMIT);
        }

        @Test
        @DisplayName("Acima do limite (Content-Length declarado) → FAILED, sem corpo")
        void aboveDeclared() {
            FetchedPage page = client.fetch(base + "/above-declared");
            assertThat(page.outcome()).isEqualTo(Outcome.FAILED);
            assertThat(page.body()).isNull();
            assertThat(page.detail()).contains("Content-Length").contains(String.valueOf(LIMIT));
        }

        @Test
        @DisplayName("64 MB declarados → recusado antes de ler o corpo; o servidor não chega a enviar tudo")
        void hugeDeclared() throws InterruptedException {
            FetchedPage page = client.fetch(base + "/huge-declared");
            assertThat(page.outcome()).isEqualTo(Outcome.FAILED);
            assertThat(page.body()).isNull();
            assertThat(writtenWhenHandlerDone()).isLessThan(CUT_BOUND);
        }

        @Test
        @DisplayName("64 MB sem tamanho declarado (chunked) → leitura interrompida após o limite, sem acumulação")
        void hugeChunked() throws InterruptedException {
            FetchedPage page = client.fetch(base + "/huge-chunked");
            assertThat(page.outcome()).isEqualTo(Outcome.FAILED);
            assertThat(page.body()).isNull();
            assertThat(page.detail()).contains("excede o limite de " + LIMIT + " bytes");
            assertThat(writtenWhenHandlerDone()).isLessThan(CUT_BOUND);
        }

        @Test
        @DisplayName("404 com corpo enorme → NOT_FOUND sem ler o corpo; recurso fechado")
        void notFoundBodyNotRead() throws InterruptedException {
            FetchedPage page = client.fetch(base + "/not-found-huge");
            assertThat(page.outcome()).isEqualTo(Outcome.NOT_FOUND);
            assertThat(writtenWhenHandlerDone()).isLessThan(CUT_BOUND);
        }

        @Test
        @DisplayName("Redirecção dentro da allowlist é seguida e o limite aplica-se ao destino")
        void redirects() throws InterruptedException {
            assertThat(client.fetch(base + "/redirect-ok").outcome()).isEqualTo(Outcome.FETCHED);
            FetchedPage huge = client.fetch(base + "/redirect-huge");
            assertThat(huge.outcome()).isEqualTo(Outcome.FAILED);
            assertThat(writtenWhenHandlerDone()).isLessThan(CUT_BOUND);
        }

        @Test
        @DisplayName("Redirecção para fora da allowlist ou para outro esquema → BLOCKED")
        void redirectOutsideIsBlocked() {
            assertThat(client.fetch(base + "/redirect-external").outcome()).isEqualTo(Outcome.BLOCKED);
            assertThat(client.fetch(base + "/redirect-https").outcome()).isEqualTo(Outcome.BLOCKED);
        }

        @Test
        @DisplayName("O cliente continua utilizável depois de uma recusa (recursos libertados)")
        void reusableAfterRejection() {
            for (int i = 0; i < 3; i++) {
                assertThat(client.fetch(base + "/above-declared").outcome()).isEqualTo(Outcome.FAILED);
                assertThat(client.fetch(base + "/below").body()).hasSize(LIMIT - 1);
            }
        }
    }
}
