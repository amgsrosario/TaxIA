package com.knowledgeflow.ingestion.atfaq;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the AT FAQ ingestion pilot.
 * <p>
 * Disabled by default: no HTTP request is ever made unless
 * {@code knowledgeflow.ingestion.at-faq.enabled=true} is set explicitly.
 */
@ConfigurationProperties(prefix = "knowledgeflow.ingestion.at-faq")
public class AtFaqProperties {

    /** Master switch — the whole module refuses to run while false. */
    private boolean enabled = false;

    /** Base URL of the source site. Only used to build the index URL. */
    private String baseUrl = "https://info.portaldasfinancas.gov.pt";

    /** Path of the FAQ index page (accordion with all tax areas). */
    private String indexPath = "/pt/apoio_contribuinte/questoes_frequentes/Pages/faqs.aspx";

    /** SSRF allowlist: hosts we are allowed to talk to. Redirects outside are blocked. */
    private List<String> allowedHosts = List.of("info.portaldasfinancas.gov.pt");

    /** Discovered category URLs must live under this path (case-insensitive). */
    private String allowedPathPrefix = "/pt/apoio_contribuinte/questoes_frequentes/";

    /** Accordion area authorized for the pilot. Other areas (IRC, IRS, …) are never followed. */
    private String topArea = "IVA";

    /**
     * Authorized subcategories inside the top area. A category link is accepted
     * when either its own label or its submenu group label matches one of these.
     */
    private List<String> allowedSubcategories = List.of("Direito à Dedução", "Taxas", "Faturação");

    /** Hard cap of FAQs processed per run (pilot limit: 80). */
    private int maxItems = 80;

    /** Hard cap of pages fetched per run (index + categories). */
    private int maxPages = 10;

    /** Pause between consecutive HTTP requests. */
    private long delayMs = 1500;

    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(20);

    /** Whole-run time budget; the run stops (truncated) when exceeded. */
    private Duration maxRunDuration = Duration.ofMinutes(10);

    /** Identifiable User-Agent — we never disguise the collector. */
    private String userAgent = "TaxIA-Research-Pilot/1.0";

    /** Maximum accepted response body size, in bytes. */
    private int maxResponseBytes = 2_000_000;

    /** Retries for transient network failures only (never for 403/429/503). */
    private int maxRetries = 2;

    /** Maximum same-host redirects followed per request. */
    private int maxRedirects = 3;

    private String sourceAuthority = "Autoridade Tributária e Aduaneira";
    private String sourceSystem = "at-faq";
    private String sourceTitle = "Portal das Finanças — Questões Frequentes";
    private String category = "IVA";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getIndexPath() { return indexPath; }
    public void setIndexPath(String indexPath) { this.indexPath = indexPath; }
    public List<String> getAllowedHosts() { return allowedHosts; }
    public void setAllowedHosts(List<String> allowedHosts) { this.allowedHosts = allowedHosts; }
    public String getAllowedPathPrefix() { return allowedPathPrefix; }
    public void setAllowedPathPrefix(String allowedPathPrefix) { this.allowedPathPrefix = allowedPathPrefix; }
    public String getTopArea() { return topArea; }
    public void setTopArea(String topArea) { this.topArea = topArea; }
    public List<String> getAllowedSubcategories() { return allowedSubcategories; }
    public void setAllowedSubcategories(List<String> allowedSubcategories) { this.allowedSubcategories = allowedSubcategories; }
    public int getMaxItems() { return maxItems; }
    public void setMaxItems(int maxItems) { this.maxItems = maxItems; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int maxPages) { this.maxPages = maxPages; }
    public long getDelayMs() { return delayMs; }
    public void setDelayMs(long delayMs) { this.delayMs = delayMs; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public Duration getMaxRunDuration() { return maxRunDuration; }
    public void setMaxRunDuration(Duration maxRunDuration) { this.maxRunDuration = maxRunDuration; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public int getMaxRedirects() { return maxRedirects; }
    public void setMaxRedirects(int maxRedirects) { this.maxRedirects = maxRedirects; }
    public String getSourceAuthority() { return sourceAuthority; }
    public void setSourceAuthority(String sourceAuthority) { this.sourceAuthority = sourceAuthority; }
    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; }
    public String getSourceTitle() { return sourceTitle; }
    public void setSourceTitle(String sourceTitle) { this.sourceTitle = sourceTitle; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String indexUrl() {
        return baseUrl + indexPath;
    }
}
