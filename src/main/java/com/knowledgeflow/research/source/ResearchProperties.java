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

    /**
     * Segunda barreira de validação de implicação jurídica (ADR-010-A; importada do piloto, KF-M02/ARCH-01).
     * Desligada por omissão. <b>Neste incremento (A2) o validador ainda não está ligado ao fluxo de investigação</b>:
     * ligar a propriedade só o disponibiliza; a integração no {@code InvestigationService} é o incremento A4. Quando
     * integrada (delimitação v3, A.4): com a barreira desligada, o modo directo mantém exactamente o comportamento
     * actual; só com ela ligada se aplica a validação de implicação, fail-closed.
     */
    private Entailment entailment = new Entailment();

    /**
     * Modo integrado no ecrã «Perguntar» (ADR-010-A; especificação KF-CONV-01 v13, §3.1). Desligado por omissão.
     * Ligado, desactiva o modo directo de criação de investigações e exige os pré-requisitos verificados no arranque
     * por {@code AskIntegrationGuard}.
     */
    private AskIntegration askIntegration = new AskIntegration();

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
    public Entailment getEntailment() { return entailment; }
    public void setEntailment(Entailment entailment) { this.entailment = entailment; }

    /** Barreira de implicação (ver o campo {@code entailment}). */
    public static class Entailment {
        /**
         * Limite superior efectivo de validações por investigação (KF-M02/CORR-05): aplicado qualquer que seja o valor
         * configurado. Só uma nova decisão (e alteração de código revista) o aumenta.
         */
        public static final int KF_M02_MAX_CHECKS_LIMIT = 4;

        /** Permite chamar o validador (uma chamada ao modelo por proposição, pela porta {@code MeteredModelClient}). */
        private boolean enabled = false;
        /** Máximo configurado de validações por investigação; ver {@link #effectiveMaxChecks()}. */
        private int maxChecks = 4;

        /**
         * Máximo efectivo: o configurado, limitado a {@link #KF_M02_MAX_CHECKS_LIMIT}. Um valor inválido (zero ou
         * negativo) falha de forma segura: 0 validações, nenhuma implicação demonstrada.
         */
        public int effectiveMaxChecks() {
            return maxChecks < 1 ? 0 : Math.min(maxChecks, KF_M02_MAX_CHECKS_LIMIT);
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxChecks() { return maxChecks; }
        public void setMaxChecks(int maxChecks) { this.maxChecks = maxChecks; }
    }

    public AskIntegration getAskIntegration() { return askIntegration; }
    public void setAskIntegration(AskIntegration askIntegration) { this.askIntegration = askIntegration; }

    /** Modo integrado (§3.1). */
    public static class AskIntegration {
        /** Interruptor do modo integrado; desligado por omissão. */
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
