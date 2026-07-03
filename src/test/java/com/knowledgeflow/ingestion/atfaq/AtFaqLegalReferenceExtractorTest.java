package com.knowledgeflow.ingestion.atfaq;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AtFaqLegalReferenceExtractorTest {

    private final AtFaqLegalReferenceExtractor extractor = new AtFaqLegalReferenceExtractor();

    @Test
    @DisplayName("Detecta artigos, números, alíneas, códigos, verbas e diplomas")
    void detectsCommonLegalPatterns() {
        List<String> refs = extractor.extract(
                "Nos termos do artigo 41.º, n.º 1, alínea a) do CIVA e da verba 2.36 da "
                        + "Lista I anexa ao Código do IVA, conforme o Decreto-Lei n.º 102/2008 "
                        + "e o art. 36.º.");
        assertThat(refs)
                .anyMatch(r -> r.toLowerCase().startsWith("artigo 41"))
                .anyMatch(r -> r.toLowerCase().startsWith("art. 36"))
                .anyMatch(r -> r.toLowerCase().contains("alínea a)"))
                .contains("CIVA")
                .anyMatch(r -> r.toLowerCase().startsWith("verba 2.36"))
                .anyMatch(r -> r.toLowerCase().startsWith("lista i"))
                .anyMatch(r -> r.toLowerCase().startsWith("decreto-lei"));
    }

    @Test
    @DisplayName("Não inventa referências quando o texto não as contém")
    void doesNotInventReferences() {
        assertThat(extractor.extract("A fatura deve ser emitida com prontidão.")).isEmpty();
        assertThat(extractor.extract("")).isEmpty();
        assertThat(extractor.extract(null)).isEmpty();
    }

    @Test
    @DisplayName("Referências duplicadas são devolvidas uma única vez")
    void deduplicatesReferences() {
        List<String> refs = extractor.extract("O CIVA e novamente o CIVA e o artigo 18.º e o artigo 18.º.");
        assertThat(refs.stream().filter("CIVA"::equals)).hasSize(1);
        assertThat(refs.stream().filter(r -> r.startsWith("artigo 18"))).hasSize(1);
    }
}
