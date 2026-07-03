package com.knowledgeflow.ingestion.atfaq;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AtFaqNormalizerTest {

    private final AtFaqNormalizer normalizer = new AtFaqNormalizer();

    @Test
    @DisplayName("Hash é estável perante diferenças irrelevantes de whitespace")
    void hashStableAcrossWhitespaceVariations() {
        String base = normalizer.contentHash(
                "Qual é a taxa aplicável?",
                "Aplica-se a taxa de 6%,\nnos termos do artigo 18.º do CIVA.\n\nSegundo parágrafo.");
        String withNoise = normalizer.contentHash(
                "  Qual   é a taxa aplicável?  ",
                "Aplica-se a taxa de 6%,   \r\n   nos termos do artigo 18.º   do CIVA.\n\n\n\nSegundo parágrafo.  \n\n");
        assertThat(withNoise).isEqualTo(base);
    }

    @Test
    @DisplayName("Hash muda quando o conteúdo semântico muda")
    void hashChangesOnContentChange() {
        String original = normalizer.contentHash("Pergunta?", "A taxa é de 6%.");
        String changedRate = normalizer.contentHash("Pergunta?", "A taxa é de 23%.");
        String changedQuestion = normalizer.contentHash("Pergunta diferente?", "A taxa é de 6%.");
        assertThat(changedRate).isNotEqualTo(original);
        assertThat(changedQuestion).isNotEqualTo(original);
    }

    @Test
    @DisplayName("Normalização preserva números, artigos, taxas, prazos, € e acentos")
    void normalizePreservesSemanticContent() {
        String raw = "  O limiar é de € 650.000,00 e a taxa   de 6%.\n\n\n"
                + "Prazo: 15 dias, artigo 41.º, n.º 1, alínea a) do CIVA.  ";
        String normalized = normalizer.normalize(raw);
        assertThat(normalized)
                .contains("€ 650.000,00")
                .contains("6%")
                .contains("15 dias")
                .contains("artigo 41.º, n.º 1, alínea a) do CIVA")
                .doesNotContain("\n\n\n")
                .doesNotContain("  ");
    }

    @Test
    @DisplayName("Normalização é idempotente")
    void normalizeIsIdempotent() {
        String once = normalizer.normalize("Texto  com\r\nvariações\n\n\n\nde espaço.");
        assertThat(normalizer.normalize(once)).isEqualTo(once);
    }

    @Test
    @DisplayName("Null é tratado como vazio")
    void nullBecomesEmpty() {
        assertThat(normalizer.normalize(null)).isEmpty();
    }
}
