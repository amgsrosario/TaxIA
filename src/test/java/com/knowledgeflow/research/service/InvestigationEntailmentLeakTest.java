package com.knowledgeflow.research.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.AITaskType;
import com.knowledgeflow.ai.spend.MeteredModelClient;
import com.knowledgeflow.research.ResearchFixtures;
import com.knowledgeflow.research.controller.ResearchTraceView;
import com.knowledgeflow.research.entity.ResearchInvestigation;
import com.knowledgeflow.research.enums.ConditionKind;
import com.knowledgeflow.research.enums.EntailmentDecision;
import com.knowledgeflow.research.enums.PropositionKind;
import com.knowledgeflow.research.service.CorpusCoverageChecker.Coverage;
import com.knowledgeflow.research.service.DiscoveryHypothesisProvider.Hypothesis;
import com.knowledgeflow.research.service.InvestigationTraceService.Trace;
import com.knowledgeflow.research.service.PropositionExtractor.ExtractedCondition;
import com.knowledgeflow.research.service.PropositionExtractor.ExtractedProposition;
import com.knowledgeflow.research.service.PropositionExtractor.Extraction;
import com.knowledgeflow.research.source.LegislationReference;
import com.knowledgeflow.research.source.OfficialSourceClient;
import com.knowledgeflow.research.source.OfficialSourceClient.FetchedPage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADR-010-A, A4, revisão R1 (MAJOR): uma resposta malformada do modelo à validação de implicação não pode fazer chegar
 * texto do modelo ao rasto persistido nem à vista. Validador <b>real</b>; o modelo devolve JSON com chaves desconhecidas
 * cujo nome é um marcador; o marcador não pode aparecer nos passos, nas razões nem no JSON da vista.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {"knowledgeflow.research.enabled=true", "knowledgeflow.research.entailment.enabled=true"})
class InvestigationEntailmentLeakTest {

    static final String URL = "https://info.portaldasfinancas.gov.pt/pt/informacao_fiscal/codigos_tributarios/cirs_rep/Pages/irs78.aspx";
    static final String ANCHOR = "aplicam-se apenas aos sujeitos passivos residentes em território português";
    static final String MARKER = "SEGREDO_PROMPT_XYZ";

    @Autowired InvestigationService service;
    @Autowired InvestigationTraceService traceService;
    @Autowired ObjectMapper objectMapper;

    @MockBean OfficialSourceClient sourceClient;
    @MockBean CorpusCoverageChecker corpusChecker;
    @MockBean DiscoveryHypothesisProvider hypothesisProvider;
    @MockBean PropositionExtractor extractor;
    @MockBean MeteredModelClient modelClient;
    @MockBean SupportVerifier supportVerifier;

    @ParameterizedTest
    @ValueSource(strings = {"topo", "dimensao", "campo"})
    void malformedValidatorResponseNeverReachesTheTrace(String where) throws Exception {
        String dims = "\"sujeito\":{\"estado\":\"CONFORME\",\"nota\":\"n\"}";
        String json = switch (where) {
            case "topo" -> "{\"veredicto\":\"SUPORTA\",\"" + MARKER + "\":1,\"dimensoes\":{" + dims + "},\"passagem\":\"p\",\"justificacao\":\"j\"}";
            case "dimensao" -> "{\"veredicto\":\"SUPORTA\",\"dimensoes\":{\"" + MARKER + "\":{\"estado\":\"CONFORME\",\"nota\":\"n\"}},\"passagem\":\"p\",\"justificacao\":\"j\"}";
            default -> "{\"veredicto\":\"SUPORTA\",\"dimensoes\":{\"sujeito\":{\"estado\":\"CONFORME\",\"nota\":\"n\",\"" + MARKER + "\":1}},\"passagem\":\"p\",\"justificacao\":\"j\"}";
        };
        UUID org = UUID.randomUUID();
        when(corpusChecker.check(any(), anyString()))
                .thenReturn(new CorpusCoverageChecker.Result(Coverage.NO_RELEVANT_QA, List.of(), "Sem Q&A relevante"));
        when(sourceClient.fetch(URL)).thenReturn(FetchedPage.fetched(200, URL, ResearchFixtures.page(ResearchFixtures.CIRS_78)));
        when(hypothesisProvider.propose(anyString(), any(), anyInt())).thenReturn(List.of(
                new Hypothesis(new LegislationReference("CIRS", 78, null), "deduções")));
        SupportVerifier.Result ok = new SupportVerifier.Result(true, 1.0, List.of());
        when(supportVerifier.check(anyString(), anyString(), anyString(), anyString(), anyString(), anyList())).thenReturn(ok);
        when(supportVerifier.checkCondition(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(ok);
        when(extractor.extract(anyString(), any())).thenReturn(new Extraction(List.of(new ExtractedProposition(
                PropositionKind.RULE, "As deduções à coleta previstas no n.º 1 do artigo 78.º do CIRS aplicam-se apenas a "
                        + "sujeitos passivos residentes em território português.", "E1", ANCHOR, "IRS", "Residentes",
                List.of(new ExtractedCondition(ConditionKind.CONDITION, "Residente", ANCHOR)))), List.of()));
        when(modelClient.complete(any())).thenAnswer(inv -> {
            AIRequest r = inv.getArgument(0);
            return r.taskType() == AITaskType.REVIEW
                    ? new AIResponse("stub", "stub", json, 1, 1, 1)
                    : new AIResponse("stub", "stub", "Resposta.", 0, 0, 0);
        });

        ResearchInvestigation i = service.investigate(org, UUID.randomUUID(), "As deduções aplicam-se a não residentes?");
        Trace t = traceService.trace(org, i.getId());

        assertThat(t.entailmentChecks()).singleElement().satisfies(c ->
                assertThat(c.getDecision()).isEqualTo(EntailmentDecision.NOT_DEMONSTRATED));
        assertThat(t.steps()).allSatisfy(s -> assertThat(String.valueOf(s.getDetail())).doesNotContain(MARKER));
        assertThat(t.entailmentChecks()).allSatisfy(c -> assertThat(c.getDecisionReason()).doesNotContain(MARKER));
        assertThat(objectMapper.writeValueAsString(ResearchTraceView.from(t))).doesNotContain(MARKER);
    }
}
