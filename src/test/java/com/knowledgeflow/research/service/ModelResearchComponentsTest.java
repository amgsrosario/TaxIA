package com.knowledgeflow.research.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.AIService;
import com.knowledgeflow.research.enums.ConditionKind;
import com.knowledgeflow.research.service.PropositionExtractor.EvidenceInput;
import com.knowledgeflow.research.service.PropositionExtractor.Extraction;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Leitura defensiva das respostas do modelo na descoberta e na estruturação. */
class ModelResearchComponentsTest {

    private final AIService ai = mock(AIService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private void modelSays(String content) {
        when(ai.complete(any())).thenReturn(new AIResponse("stub", "stub", content, 0, 0, 0));
    }

    @Test
    @DisplayName("Descoberta: só códigos do catálogo, artigos plausíveis e até ao máximo pedido")
    void discoveryFiltersHypotheses() {
        modelSays("""
                Aqui está: {"hypotheses":[
                  {"code":"CIRS","article":78,"suffix":null,"why":"deduções"},
                  {"code":"EBF","article":1,"why":"fora do catálogo"},
                  {"code":"CIVA","article":0},
                  {"code":"CIRS","article":78,"suffix":"A"},
                  {"code":"LGT","article":1}]}""");
        var provider = new ModelDiscoveryHypothesisProvider(ai, mapper);

        var out = provider.propose("pergunta", Set.of("CIRS", "CIVA", "CIRC", "LGT"), 2);

        assertThat(out).extracting(h -> h.reference().key()).containsExactly("CIRS:78", "CIRS:78-A");
    }

    @Test
    @DisplayName("Descoberta: JSON ilegível ou falha do modelo → nenhuma hipótese")
    void discoveryToleratesFailures() {
        var provider = new ModelDiscoveryHypothesisProvider(ai, mapper);
        modelSays("não sei");
        assertThat(provider.propose("p", Set.of("CIRS"), 3)).isEmpty();
        when(ai.complete(any())).thenThrow(new IllegalStateException("provider down"));
        assertThat(provider.propose("p", Set.of("CIRS"), 3)).isEmpty();
    }

    @Test
    @DisplayName("Estruturação: lê proposições, condições/excepções e conflitos válidos")
    void extractionParsesStructure() {
        modelSays("""
                {"propositions":[
                  {"kind":"RULE","statement":"S1","evidence":"E1","anchor":"A1","scope":"IRS","applicability":"residentes",
                   "conditions":[{"kind":"CONDITION","description":"C1","anchor":"AC1"},
                                 {"kind":"EXCEPTION","description":"X1","anchor":"AX1"}]},
                  {"kind":"PROPOSITION","statement":"S2","evidence":"E1","anchor":"A2"},
                  {"kind":"RULE","statement":"","evidence":"E1"}],
                 "conflicts":[{"first":0,"second":1,"note":"n"},{"first":0,"second":0},{"first":0,"second":9}]}""");
        var extractor = new ModelPropositionExtractor(ai, mapper);

        Extraction e = extractor.extract("q", List.of(new EvidenceInput("E1", "artigo 78.º do CIRS", "texto")));

        assertThat(e.propositions()).hasSize(2);
        assertThat(e.propositions().get(0).conditions()).extracting(c -> c.kind())
                .containsExactly(ConditionKind.CONDITION, ConditionKind.EXCEPTION);
        assertThat(e.conflicts()).hasSize(1);
        assertThat(e.conflicts().get(0).first()).isZero();
        assertThat(e.conflicts().get(0).second()).isEqualTo(1);
    }

    @Test
    @DisplayName("Estruturação: sem evidência não chama o modelo; JSON ilegível → extracção vazia")
    void extractionToleratesFailures() {
        var extractor = new ModelPropositionExtractor(ai, mapper);
        assertThat(extractor.extract("q", List.of()).propositions()).isEmpty();
        modelSays("{ inválido");
        assertThat(extractor.extract("q", List.of(new EvidenceInput("E1", "r", "t"))).propositions()).isEmpty();
    }
}
