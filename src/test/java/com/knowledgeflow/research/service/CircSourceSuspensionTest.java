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
        // Um passo SKIPPED por referência: a da pergunta (art. 87.º) e a hipótese do modelo (art. 90.º).
        assertThat(t.steps()).filteredOn(s -> s.getPhase() == ResearchPhase.CONSULTATION && s.getOutcome() == StepOutcome.SKIPPED
                        && s.getDetail() != null && s.getDetail().contains("Fonte do CIRC no Portal das Finanças suspensa"))
                .extracting(s -> s.getTarget())
                .hasSize(2)
                .anySatisfy(target -> assertThat(target).contains("87"))
                .anySatisfy(target -> assertThat(target).contains("90"));
        // A limitação da resposta menciona a suspensão e o perímetro já não inclui o CIRC.
        assertThat(t.investigation().getLimitations()).contains("Fontes suspensas").contains("(CIRS, CIVA, LGT)");
        // O modelo de descoberta não recebe o CIRC entre os códigos permitidos.
        verify(hypothesisProvider).propose(anyString(), argThat(codes -> !codes.contains("CIRC") && codes.contains("CIRS")), anyInt());
        // Sem fundamento: resposta-limite.
        assertThat(t.investigation().getAnswerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
    }

    @Test
    void aFetchedPageWhoseFinalUrlIsASuspendedSourceNeverBecomesEvidence() {
        UUID org = UUID.randomUUID();
        String cirs78 = "https://info.portaldasfinancas.gov.pt/pt/informacao_fiscal/codigos_tributarios/cirs_rep/Pages/irs78.aspx";
        String circFinal = "https://info.portaldasfinancas.gov.pt/pt/informacao_fiscal/codigos_tributarios/circ/Pages/irc78.aspx";
        when(corpusChecker.check(any(), anyString()))
                .thenReturn(new CorpusCoverageChecker.Result(Coverage.NO_RELEVANT_QA, List.of(), "Sem Q&A relevante"));
        when(hypothesisProvider.propose(anyString(), any(), anyInt())).thenReturn(List.of(
                new Hypothesis(new LegislationReference("CIRS", 78, null), "deduções")));
        // Cliente substituído: simula uma redirecção para o CIRC que o cliente real recusaria.
        when(sourceClient.fetch(cirs78)).thenReturn(FetchedPage.fetched(200, circFinal,
                com.knowledgeflow.research.ResearchFixtures.page(com.knowledgeflow.research.ResearchFixtures.CIRS_78)));
        when(extractor.extract(anyString(), any())).thenReturn(new Extraction(List.of(), List.of()));
        when(modelClient.complete(any())).thenReturn(new AIResponse("stub", "stub", "Resposta.", 0, 0, 0));

        ResearchInvestigation i = service.investigate(org, UUID.randomUUID(), "As deduções à coleta aplicam-se a não residentes?");
        Trace t = traceService.trace(org, i.getId());

        assertThat(t.evidence()).isEmpty();
        assertThat(t.steps()).anySatisfy(s -> {
            assertThat(s.getOutcome()).isEqualTo(StepOutcome.BLOCKED);
            assertThat(s.getDetail()).contains("Fonte suspensa");
        });
        verify(extractor, never()).extract(anyString(), any());
    }
}
