package com.knowledgeflow.ingestion.atfaq;

import com.knowledgeflow.ingestion.atfaq.AtFaqExceptions.FetchException;
import com.knowledgeflow.ingestion.atfaq.AtFaqExceptions.SecurityBlockedException;
import com.knowledgeflow.ingestion.atfaq.AtFaqExceptions.SourceBlockedException;
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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Conservative HTTP client for the AT FAQ pilot.
 * <ul>
 *   <li>One request at a time, with a configurable pause between requests.</li>
 *   <li>Identifiable User-Agent; no cookies; no authentication; no JS.</li>
 *   <li>Host allowlist enforced on every URL, including redirect targets (SSRF guard).</li>
 *   <li>403/429/503 abort the whole run — blocks are respected, never worked around.</li>
 *   <li>Retries only for transient network failures, never for HTTP blocks.</li>
 *   <li>Response size limit; conditional GET (ETag/Last-Modified) to reuse cache.</li>
 * </ul>
 */
@Component
public class AtFaqHttpClient {

    private static final Logger log = LoggerFactory.getLogger(AtFaqHttpClient.class);
    private static final Pattern CHARSET_PATTERN =
            Pattern.compile("charset\\s*=\\s*\"?([\\w.:\\-]+)\"?", Pattern.CASE_INSENSITIVE);

    private final AtFaqProperties properties;
    private final HttpClient httpClient;
    private final Sleeper sleeper;

    /** Small indirection so tests don't have to actually sleep. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** Per-run HTTP statistics (requests, retries, status code classes). */
    public static class Stats {
        private int requests;
        private int retries;
        private final Map<Integer, Integer> statusCodes = new HashMap<>();

        void recordRequest() { requests++; }
        void recordRetry() { retries++; }
        void recordStatus(int code) { statusCodes.merge(code, 1, Integer::sum); }

        public int requests() { return requests; }
        public int retries() { return retries; }
        public Map<Integer, Integer> statusCodes() { return Map.copyOf(statusCodes); }
    }

    public record FetchResult(
            int statusCode,
            String body,
            String etag,
            String lastModified,
            boolean notModified,
            String finalUrl) {
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AtFaqHttpClient(AtFaqProperties properties) {
        this(properties, Thread::sleep);
    }

    AtFaqHttpClient(AtFaqProperties properties, Sleeper sleeper) {
        this.properties = properties;
        this.sleeper = sleeper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                // Redirects handled manually so every hop goes through the allowlist.
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE))
                .build();
    }

    /**
     * Fetches a page. {@code etag}/{@code lastModified} enable a conditional GET:
     * a 304 answer is returned as {@code notModified=true} with a null body.
     */
    public FetchResult fetch(String url, String etag, String lastModified, Stats stats) {
        URI uri = validateUrl(url);
        int redirects = 0;

        while (true) {
            FetchResult result = executeWithRetries(uri, etag, lastModified, stats);

            if (isRedirect(result.statusCode())) {
                if (++redirects > properties.getMaxRedirects()) {
                    throw new FetchException("Too many redirects for " + url);
                }
                uri = resolveRedirect(uri, result);
                continue;
            }
            return result;
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private FetchResult executeWithRetries(URI uri, String etag, String lastModified, Stats stats) {
        IOException lastFailure = null;

        for (int attempt = 0; attempt <= properties.getMaxRetries(); attempt++) {
            if (attempt > 0) {
                stats.recordRetry();
                pause(500L * attempt);
            }
            pause(stats.requests() == 0 ? 0 : properties.getDelayMs());

            try {
                return executeOnce(uri, etag, lastModified, stats);
            } catch (IOException e) {
                // Transient network failure — the only case we retry.
                lastFailure = e;
                log.warn("Transient failure fetching {} (attempt {}): {}", uri, attempt + 1, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException("Interrupted while fetching " + uri, e);
            }
        }
        throw new FetchException("Failed to fetch %s after %d attempts".formatted(
                uri, properties.getMaxRetries() + 1), lastFailure);
    }

    private FetchResult executeOnce(URI uri, String etag, String lastModified, Stats stats)
            throws IOException, InterruptedException {

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(properties.getReadTimeout())
                .header("User-Agent", properties.getUserAgent())
                .header("Accept", "text/html")
                .GET();
        if (etag != null && !etag.isBlank()) builder.header("If-None-Match", etag);
        if (lastModified != null && !lastModified.isBlank()) builder.header("If-Modified-Since", lastModified);

        stats.recordRequest();
        HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        int status = response.statusCode();
        stats.recordStatus(status);

        if (status == 403 || status == 429 || status == 503) {
            throw new SourceBlockedException(status, uri.toString());
        }
        if (status == 304) {
            return new FetchResult(status, null, etag, lastModified, true, uri.toString());
        }
        if (isRedirect(status)) {
            String location = response.headers().firstValue("Location").orElse(null);
            if (location == null) throw new FetchException("Redirect without Location from " + uri);
            return new FetchResult(status, location, null, null, false, uri.toString());
        }
        if (status != 200) {
            throw new FetchException("Unexpected HTTP %d from %s".formatted(status, uri));
        }

        byte[] bytes = response.body();
        if (bytes.length > properties.getMaxResponseBytes()) {
            throw new FetchException("Response from %s exceeds the %d-byte limit (%d bytes)"
                    .formatted(uri, properties.getMaxResponseBytes(), bytes.length));
        }

        Charset charset = charsetOf(response.headers().firstValue("Content-Type").orElse(null));
        return new FetchResult(
                status,
                new String(bytes, charset),
                response.headers().firstValue("ETag").orElse(null),
                response.headers().firstValue("Last-Modified").orElse(null),
                false,
                uri.toString());
    }

    private URI resolveRedirect(URI current, FetchResult redirect) {
        URI target;
        try {
            target = current.resolve(redirect.body().trim());
        } catch (IllegalArgumentException e) {
            throw new SecurityBlockedException("Invalid redirect Location from " + current);
        }
        // Every redirect hop must stay inside the allowlist — external hops are an abort.
        return validateUrl(target.toString());
    }

    /** Validates scheme + allowlisted host. Throws SecurityBlockedException otherwise. */
    public URI validateUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new SecurityBlockedException("Invalid URL: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new SecurityBlockedException("Unsupported scheme in URL: " + url);
        }
        if (uri.getUserInfo() != null) {
            throw new SecurityBlockedException("Credentials in URL are not allowed: " + url);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = properties.getAllowedHosts().stream()
                .anyMatch(h -> h.equalsIgnoreCase(host));
        if (!allowed) {
            throw new SecurityBlockedException(
                    "Host '%s' is not in the allowlist %s".formatted(host, properties.getAllowedHosts()));
        }
        return uri;
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
                    // fall through to UTF-8
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private void pause(long millis) {
        if (millis <= 0) return;
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("Interrupted during request pause", e);
        }
    }
}
