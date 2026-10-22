package com.knowledgeflow.research.source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cliente HTTP conservador para fontes oficiais, com as mesmas garantias do cliente do piloto
 * AT-FAQ ({@code AtFaqHttpClient}): só GET, sem cookies nem autenticação, User-Agent
 * identificável, allowlist de hosts em cada URL e em cada redirecção (SSRF), pausa entre pedidos.
 * 403/429/503 são bloqueios respeitados, nunca contornados.
 * <p>
 * Só HTTPS: o esquema {@code http} só existe no construtor de pacote usado pelos testes com
 * servidor local; não há configuração que o active em produção.
 * <p>
 * O corpo é lido em stream com limite: um {@code Content-Length} acima de {@code maxResponseBytes}
 * é recusado sem ler o corpo, e uma resposta sem tamanho declarado (chunked) é interrompida assim
 * que ultrapassa o limite. A memória usada fica limitada a {@code maxResponseBytes} mais um buffer.
 * Todo o corpo — incluindo o de redirecções e erros — é fechado. Uma resposta excessiva dá
 * {@code FAILED}: não há conteúdo a avaliar e nenhuma evidência é criada.
 * <p>
 * Devolve o resultado em vez de lançar excepção, porque na investigação um 404, um bloqueio ou uma
 * recusa por tamanho é informação a registar no passo de consulta.
 */
public class OfficialSourceHttpClient implements OfficialSourceClient {

    private static final Logger log = LoggerFactory.getLogger(OfficialSourceHttpClient.class);
    private static final Pattern CHARSET_PATTERN =
            Pattern.compile("charset\\s*=\\s*\"?([\\w.:\\-]+)\"?", Pattern.CASE_INSENSITIVE);
    private static final int BUFFER_SIZE = 8192;

    private final ResearchProperties properties;
    private final Set<String> allowedSchemes;
    private final HttpClient httpClient;
    private long lastRequestAt;

    public OfficialSourceHttpClient(ResearchProperties properties) {
        this(properties, Set.of("https"));
    }

    /** Só para testes com servidor HTTP local, no mesmo pacote. */
    OfficialSourceHttpClient(ResearchProperties properties, Set<String> allowedSchemes) {
        this.properties = properties;
        this.allowedSchemes = Set.copyOf(allowedSchemes);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE))
                .build();
    }

    /** O corpo excedeu o limite configurado; a leitura foi interrompida. */
    static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException(String message) {
            super(message);
        }
    }

    @Override
    public synchronized FetchedPage fetch(String url) {
        URI uri;
        try {
            uri = validateUrl(url);
        } catch (IllegalArgumentException e) {
            return FetchedPage.blocked(null, url, e.getMessage());
        }
        for (int redirects = 0; ; redirects++) {
            try {
                pause();
                HttpResponse<InputStream> response = httpClient.send(HttpRequest.newBuilder(uri)
                        .timeout(properties.getReadTimeout())
                        .header("User-Agent", properties.getUserAgent())
                        .header("Accept", "text/html")
                        .GET()
                        .build(), HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    int status = response.statusCode();
                    if (status == 403 || status == 429 || status == 503) {
                        return FetchedPage.blocked(status, uri.toString(), "Fonte recusou o pedido (HTTP " + status + ")");
                    }
                    if (status == 404 || status == 410) {
                        return FetchedPage.notFound(status, uri.toString());
                    }
                    if (isRedirect(status)) {
                        String location = response.headers().firstValue("Location").orElse(null);
                        if (location == null) return FetchedPage.failed(status, uri.toString(), "Redirecção sem Location");
                        if (redirects >= properties.getMaxRedirects()) {
                            return FetchedPage.failed(status, uri.toString(), "Demasiadas redirecções");
                        }
                        uri = validateUrl(uri.resolve(location.trim()).toString());
                        continue;
                    }
                    if (status != 200) {
                        return FetchedPage.failed(status, uri.toString(), "HTTP " + status + " inesperado");
                    }
                    OptionalLong declared = response.headers().firstValueAsLong("Content-Length");
                    if (declared.isPresent() && declared.getAsLong() > properties.getMaxResponseBytes()) {
                        return FetchedPage.failed(status, uri.toString(), "Resposta recusada: Content-Length %d excede o limite de %d bytes"
                                .formatted(declared.getAsLong(), properties.getMaxResponseBytes()));
                    }
                    byte[] bytes = readLimited(body, properties.getMaxResponseBytes());
                    Charset charset = charsetOf(response.headers().firstValue("Content-Type").orElse(null));
                    return FetchedPage.fetched(status, uri.toString(), new String(bytes, charset));
                }
            } catch (ResponseTooLargeException e) {
                log.warn("Resposta de {} recusada: {}", uri, e.getMessage());
                return FetchedPage.failed(200, uri.toString(), "Resposta recusada: " + e.getMessage());
            } catch (IllegalArgumentException e) {
                return FetchedPage.blocked(null, url, e.getMessage());
            } catch (IOException e) {
                log.warn("Falha de rede ao consultar {}: {}", uri, e.getMessage());
                return FetchedPage.failed(null, uri.toString(), "Falha de rede: " + e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return FetchedPage.failed(null, uri.toString(), "Interrompido");
            }
        }
    }

    /**
     * Lê no máximo {@code maxBytes}; um byte a mais interrompe a leitura. A memória retida nunca
     * excede {@code maxBytes} mais um buffer.
     */
    static byte[] readLimited(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int n;
        while ((n = in.read(buffer, 0, (int) Math.min(buffer.length, maxBytes - total + 1))) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new ResponseTooLargeException("corpo excede o limite de %d bytes".formatted(maxBytes));
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** Esquema permitido (HTTPS), sem credenciais e host na allowlist; caso contrário IllegalArgumentException. */
    URI validateUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL inválido: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!allowedSchemes.contains(scheme)) {
            throw new IllegalArgumentException("Esquema não permitido (só HTTPS): " + url);
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Credenciais em URL não são permitidas");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (properties.getAllowedHosts().stream().noneMatch(h -> h.equalsIgnoreCase(host))) {
            throw new IllegalArgumentException("Host '%s' fora da allowlist".formatted(host));
        }
        return uri;
    }

    private void pause() throws InterruptedException {
        long wait = lastRequestAt + properties.getDelayMs() - System.currentTimeMillis();
        if (lastRequestAt > 0 && wait > 0) Thread.sleep(wait);
        lastRequestAt = System.currentTimeMillis();
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            Matcher m = CHARSET_PATTERN.matcher(contentType);
            if (m.find()) {
                try {
                    return Charset.forName(m.group(1));
                } catch (Exception ignored) {
                    // UTF-8 por omissão
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}
