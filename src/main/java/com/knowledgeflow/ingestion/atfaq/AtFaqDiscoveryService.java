package com.knowledgeflow.ingestion.atfaq;

import com.knowledgeflow.ingestion.atfaq.AtFaqHtmlParser.IndexLink;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Locates the authorized category pages starting from the FAQ index page.
 * <p>
 * Only the configured top area (IVA) and the authorized subcategories are
 * followed. Everything else — other tax areas, external hosts, URLs outside
 * the FAQ path — is rejected and reported, never fetched.
 */
@Service
public class AtFaqDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(AtFaqDiscoveryService.class);

    private final AtFaqProperties properties;
    private final AtFaqHtmlParser parser;

    public record DiscoveredCategory(String subcategory, String url) {
    }

    public record DiscoveryOutcome(
            List<DiscoveredCategory> categories,
            List<String> rejectedUrls,
            int duplicatesSkipped) {
    }

    public AtFaqDiscoveryService(AtFaqProperties properties, AtFaqHtmlParser parser) {
        this.properties = properties;
        this.parser = parser;
    }

    /** Pure function over the index HTML — no HTTP here, easy to test with fixtures. */
    public DiscoveryOutcome discoverFromIndexHtml(String indexHtml, String indexUrl) {
        List<IndexLink> allLinks = parser.parseIndex(indexHtml, indexUrl, properties.getTopArea());

        Map<String, DiscoveredCategory> byNormalizedUrl = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();
        int duplicates = 0;

        for (IndexLink link : allLinks) {
            String subcategory = authorizedSubcategory(link);
            if (subcategory == null) {
                continue; // Not in the pilot scope — silently out (not an anomaly).
            }
            String validated = validateCategoryUrl(link.url());
            if (validated == null) {
                rejected.add(link.url());
                continue;
            }
            String key = validated.toLowerCase(Locale.ROOT);
            if (byNormalizedUrl.containsKey(key)) {
                duplicates++;
                continue;
            }
            byNormalizedUrl.put(key, new DiscoveredCategory(subcategory, validated));
        }

        List<DiscoveredCategory> categories = new ArrayList<>(byNormalizedUrl.values());
        log.info("AT FAQ discovery: {} authorized categories, {} rejected URLs, {} duplicates",
                categories.size(), rejected.size(), duplicates);
        return new DiscoveryOutcome(categories, rejected, duplicates);
    }

    /**
     * A link is authorized when its own label OR its submenu group label matches
     * an allowed subcategory ("Direito à Dedução" and "Taxas" match by link
     * label; "Faturação" is a submenu group whose child links inherit it).
     * Returns the subcategory name to record, or null when out of scope.
     */
    private String authorizedSubcategory(IndexLink link) {
        for (String allowed : properties.getAllowedSubcategories()) {
            String normalizedAllowed = AtFaqHtmlParser.normalizeLabel(allowed);
            if (equalsIgnoreCase(link.linkLabel(), normalizedAllowed)) {
                return allowed;
            }
            if (equalsIgnoreCase(link.groupLabel(), normalizedAllowed)) {
                return allowed;
            }
        }
        return null;
    }

    /**
     * Accepts only absolute URLs (already resolved against the index base)
     * pointing at an allowlisted host under the authorized path prefix.
     */
    private String validateCategoryUrl(String url) {
        if (url == null || url.isBlank()) return null;
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return null;
        }
        if (uri.getHost() == null || uri.getScheme() == null) return null;
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) return null;

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowedHost = properties.getAllowedHosts().stream()
                .anyMatch(h -> h.equalsIgnoreCase(host));
        if (!allowedHost) return null;

        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!path.toLowerCase(Locale.ROOT).startsWith(
                properties.getAllowedPathPrefix().toLowerCase(Locale.ROOT))) {
            return null;
        }
        return url;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }
}
