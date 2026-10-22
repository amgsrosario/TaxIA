package com.knowledgeflow.research.source;

import java.io.IOException;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cliente HTTP conservador para fontes oficiais, com as mesmas garantias do cliente do piloto
 * AT-FAQ ({@code AtFaqHttpClient}): só GET, sem cookies nem autenticação, User-Agent
 * identificável, allowlist de hosts em cada URL e em cada redirecção (SSRF), pausa entre pedidos,
 * limite de tamanho. 403/429/503 são bloqueios respeitados, nunca contornados.
 * <p>
 * Ao contrário daquele cliente, devolve o resultado em vez de lançar excepção, porque na
 * investigação um 404 ou um bloqueio é informação a registar (passo e memória crítica).
 */
public class OfficialSourceHttpClient implements OfficialSourceClient {

    private static final Logger log = LoggerFactory.getLogger(OfficialSourceHttpClient.class);
    private static final Pattern CHARSET_PATTERN =
            Pattern.compile("charset\\s*=\\s*\"?([\\w.:\\-]+)\"?", Pattern.CASE_INSENSITIVE);

    private final ResearchProperties properties;
    private final HttpClient httpClient;
    private long lastRequestAt;

    public OfficialSourceHttpClient(ResearchProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE))
                .build();
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
            FetchedPage page;
            String location;
            try {
                pause();
                HttpResponse<byte[]> response = httpClient.send(HttpRequest.newBuilder(uri)
                        .timeout(properties.getReadTimeout())
                        .header("User-Agent", properties.getUserAgent())
                        .header("Accept", "text/html")
                        .GET()
                        .build(), HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status == 403 || status == 429 || status == 503) {
                    return FetchedPage.blocked(status, uri.toString(), "Fonte recusou o pedido (HTTP " + status + ")");
                }
                if (status == 404 || status == 410) {
                    return FetchedPage.notFound(status, uri.toString());
                }
                if (isRedirect(status)) {
                    location = response.headers().firstValue("Location").orElse(null);
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
                byte[] bytes = response.body();
                if (bytes.length > properties.getMaxResponseBytes()) {
                    return FetchedPage.failed(status, uri.toString(), "Resposta excede o limite de tamanho");
                }
                Charset charset = charsetOf(response.headers().firstValue("Content-Type").orElse(null));
                page = FetchedPage.fetched(status, uri.toString(), new String(bytes, charset));
            } catch (IllegalArgumentException e) {
                return FetchedPage.blocked(null, url, e.getMessage());
            } catch (IOException e) {
                log.warn("Falha de rede ao consultar {}: {}", uri, e.getMessage());
                return FetchedPage.failed(null, uri.toString(), "Falha de rede: " + e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return FetchedPage.failed(null, uri.toString(), "Interrompido");
            }
            return page;
        }
    }

    /** Esquema http(s), sem credenciais e host na allowlist; caso contrário IllegalArgumentException. */
    URI validateUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL inválido: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new IllegalArgumentException("Esquema não suportado: " + url);
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
