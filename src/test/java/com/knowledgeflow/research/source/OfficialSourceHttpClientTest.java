package com.knowledgeflow.research.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.research.source.OfficialSourceClient.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Garantias SSRF do cliente de fontes oficiais; nenhum teste faz pedidos de rede. */
class OfficialSourceHttpClientTest {

    private final OfficialSourceHttpClient client = new OfficialSourceHttpClient(new ResearchProperties());

    @Test
    @DisplayName("Só hosts da allowlist, só http(s), sem credenciais no URL")
    void allowlistAndScheme() {
        assertThat(client.validateUrl("https://info.portaldasfinancas.gov.pt/pt/x.aspx").getHost())
                .isEqualTo("info.portaldasfinancas.gov.pt");
        assertThatThrownBy(() -> client.validateUrl("https://example.com/")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.validateUrl("https://info.portaldasfinancas.gov.pt.evil.test/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.validateUrl("ftp://info.portaldasfinancas.gov.pt/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.validateUrl("https://user:pw@info.portaldasfinancas.gov.pt/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.validateUrl("https://www.portaldasfinancas.gov.pt/"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("URL fora da allowlist é BLOCKED sem pedido de rede")
    void fetchOutsideAllowlistIsBlocked() {
        var page = client.fetch("https://example.com/artigo.aspx");
        assertThat(page.outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(page.body()).isNull();
    }
}
