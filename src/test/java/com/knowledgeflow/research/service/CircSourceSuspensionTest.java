package com.knowledgeflow.research.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.documented.AnswerType;
import com.knowledgeflow.ai.spend.MeteredModelClient;
import com.knowledgeflow.research.entity.ResearchInvestigation;
import com.knowledgeflow.research.enums.ResearchPhase;
import com.knowledgeflow.research.enums.StepOutcome;
import com.knowledgeflow.research.service.CorpusCoverageChecker.Coverage;
import com.knowledgeflow.research.service.DiscoveryHypothesisProvider.Hypothesis;
import com.knowledgeflow.research.service.InvestigationTraceService.Trace;
import com.knowledgeflow.research.service.PropositionExtractor.Extraction;
import com.knowledgeflow.research.source.LegislationReference;
import com.knowledgeflow.research.source.OfficialSourceClient;
import com.knowledgeflow.research.source.OfficialSourceClient.FetchedPage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ordem executiva de 23/11/2026, ponto 4.1: a fonte histórica do CIRC no Portal das Finanças fica suspensa como
 * fundamento. Uma referência ao CIRC (na pergunta ou proposta pelo modelo) não é consultada, não origina evidência e
 * fica registada como passo SKIPPED com o motivo; o modelo de descoberta deixa de receber o CIRC entre os códigos
 * permitidos. Com a barreira desligada (omissão), sem nenhuma outra evidência, a investigação termina em resposta-limite.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "knowledgeflow.research.enabled=true")
class CircSourceSuspensionTest {

    @Autowired InvestigationService service;
    @Autowired InvestigationTraceService traceService;

    @MockBean OfficialSourceClient sourceClient;
    @MockBean CorpusCoverageChecker corpusChecker;
    @MockBean DiscoveryHypothesisProvider hypothesisProvider;
    @MockBean PropositionExtractor extractor;
    @MockBean MeteredModelClient modelClient;

    @Test
    void circReferencesAreNeitherConsultedNorUsedAsEvidence() {
        UUID org = UUID.randomUUID();
        when(corpusChecker.check(any(), anyString()))
                .thenReturn(new CorpusCoverageChecker.Result(Coverage.NO_RELEVANT_QA, List.of(), "Sem Q&A relevante"));
        when(hypothesisProvider.propose(anyString(), any(), anyInt())).thenReturn(List.of(
                new Hypothesis(new LegislationReference("CIRC", 90, null), "hipótese do modelo sobre o IRC")));
        when(sourceClient.fetch(anyString())).thenReturn(FetchedPage.notFound(404, "x"));
        when(extractor.extract(anyString(), any())).thenReturn(new Extraction(List.of(), List.of()));
        when(modelClient.complete(any())).thenReturn(new AIResponse("stub", "stub", "Resposta.", 0, 0, 0));

        ResearchInvestigation i = service.investigate(org, UUID.randomUUID(),
                "Qual é a taxa do IRC prevista no artigo 87.º do CIRC?");
        Trace t = traceService.trace(org, i.getId());

        // Nenhuma página do CIRC é pedida ao Portal.
        verify(sourceClient, never()).fetch(argThat(u -> u != null && u.contains("/circ/")));
        // Nenhuma evidência CIRC.
        assertThat(t.evidence()).noneMatch(e -> e.getReferenceKey() != null && e.getReferenceKey().startsWith("CIRC"));
        // Passos SKIPPED com o motivo da suspensão, para a referência da pergunta e para a hipótese do modelo.
        assertThat(t.steps()).filteredOn(s -> s.getPhase() == ResearchPhase.CONSULTATION && s.getOutcome() == StepOutcome.SKIPPED)
                .extracting(s -> s.getDetail())
                .anySatisfy(d -> assertThat(d).contains("Fonte do CIRC no Portal das Finanças suspensa"));
        // O modelo de descoberta não recebe o CIRC entre os códigos permitidos.
        verify(hypothesisProvider).propose(anyString(), argThat(codes -> !codes.contains("CIRC") && codes.contains("CIRS")), anyInt());
        // Sem fundamento: resposta-limite.
        assertThat(t.investigation().getAnswerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
    }
}
