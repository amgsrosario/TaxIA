package com.knowledgeflow.research.source;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração do percurso de investigação (KF-M01, ADR-010).
 * <p>
 * Desligado por omissão: sem {@code knowledgeflow.research.enabled=true} não há endpoint nem
 * pedido HTTP de saída. O acesso de saída é só de leitura, sem autenticação, cookies nem
 * submissão, e limitado à allowlist (Aditamento Operacional §3: legislação oficial e Portal das
 * Finanças / AT).
 */
@ConfigurationProperties(prefix = "knowledgeflow.research")
public class ResearchProperties {

    /** Interruptor geral do módulo. */
    private boolean enabled = false;

    /** Base dos códigos tributários consolidados publicados pela AT. */
    private String legislationBaseUrl = "https://info.portaldasfinancas.gov.pt/pt/informacao_fiscal/codigos_tributarios";

    /** Allowlist SSRF: só estes hosts, incluindo destinos de redirecção. */
    private List<String> allowedHosts = List.of("info.portaldasfinancas.gov.pt");

    /** Máximo de consultas a fontes oficiais por investigação. */
    private int maxConsultations = 4;

    /** Máximo de hipóteses de descoberta pedidas ao modelo. */
    private int maxModelHypotheses = 3;

    private long delayMs = 1500;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(20);
    private int maxRedirects = 3;
    private int maxResponseBytes = 2_000_000;
    private String userAgent = "TaxIA-Research-Pilot/1.0";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getLegislationBaseUrl() { return legislationBaseUrl; }
    public void setLegislationBaseUrl(String legislationBaseUrl) { this.legislationBaseUrl = legislationBaseUrl; }
    public List<String> getAllowedHosts() { return allowedHosts; }
    public void setAllowedHosts(List<String> allowedHosts) { this.allowedHosts = allowedHosts; }
    public int getMaxConsultations() { return maxConsultations; }
    public void setMaxConsultations(int maxConsultations) { this.maxConsultations = maxConsultations; }
    public int getMaxModelHypotheses() { return maxModelHypotheses; }
    public void setMaxModelHypotheses(int maxModelHypotheses) { this.maxModelHypotheses = maxModelHypotheses; }
    public long getDelayMs() { return delayMs; }
    public void setDelayMs(long delayMs) { this.delayMs = delayMs; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public int getMaxRedirects() { return maxRedirects; }
    public void setMaxRedirects(int maxRedirects) { this.maxRedirects = maxRedirects; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
}
