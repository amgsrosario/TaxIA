package com.knowledgeflow.research.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.spend.MeteredModelClient;
import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.repository.AuditEventRepository;
import com.knowledgeflow.research.ResearchFixtures;
import com.knowledgeflow.research.entity.ResearchInvestigation;
import com.knowledgeflow.research.enums.ConditionKind;
import com.knowledgeflow.research.enums.PropositionKind;
import com.knowledgeflow.research.enums.PropositionStatus;
import com.knowledgeflow.research.enums.ResearchPhase;
import com.knowledgeflow.research.enums.StepOrigin;
import com.knowledgeflow.research.repository.EntailmentCheckRepository;
import com.knowledgeflow.research.service.CorpusCoverageChecker.Coverage;
import com.knowledgeflow.research.service.DiscoveryHypothesisProvider.Hypothesis;
import com.knowledgeflow.research.service.InvestigationTraceService.Trace;
import com.knowledgeflow.research.service.PropositionExtractor.ExtractedCondition;
import com.knowledgeflow.research.service.PropositionExtractor.ExtractedProposition;
import com.knowledgeflow.research.service.PropositionExtractor.Extraction;
import com.knowledgeflow.research.source.LegislationReference;
import com.knowledgeflow.research.source.OfficialSourceClient;
import com.knowledgeflow.research.source.OfficialSourceClient.FetchedPage;
import com.knowledgeflow.research.source.ResearchProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADR-010-A, A4, barreira <b>desligada</b> (omissão): o modo directo mantém a decisão e os efeitos persistidos de antes
 * do A4. Nenhuma linha de implicação, nenhum passo ENTAILMENT/VERIFICATION/MODEL, metadados de auditoria no formato
 * anterior, nenhuma chamada ao validador nem ao {@link SupportVerifier}, e a decisão por âncora literal e números.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "knowledgeflow.research.enabled=true")
class InvestigationEntailmentOffTest {

    static final String URL = "https://info.portaldasfinancas.gov.pt/pt/informacao_fiscal/codigos_tributarios/cirs_rep/Pages/irs78.aspx";
    static final String ANCHOR = "aplicam-se apenas aos sujeitos passivos residentes em território português";

    @Autowired InvestigationService service;
    @Autowired InvestigationTraceService traceService;
    @Autowired ResearchProperties properties;
    @Autowired EntailmentCheckRepository entailmentRepository;
    @Autowired AuditEventRepository auditEventRepository;

    @MockBean OfficialSourceClient sourceClient;
    @MockBean CorpusCoverageChecker corpusChecker;
    @MockBean DiscoveryHypothesisProvider hypothesisProvider;
    @MockBean PropositionExtractor extractor;
    @MockBean MeteredModelClient modelClient;
    @MockBean EntailmentValidator validator;
    @SpyBean SupportVerifier supportVerifier;

    @Test
    void barrierOffKeepsThePreviousDecisionAndPersistedEffects() {
        assertThat(properties.getEntailment().isEnabled()).isFalse();
        UUID org = UUID.randomUUID();
        when(corpusChecker.check(any(), anyString()))
                .thenReturn(new CorpusCoverageChecker.Result(Coverage.NO_RELEVANT_QA, List.of(), "Sem Q&A relevante"));
        when(sourceClient.fetch(URL)).thenReturn(FetchedPage.fetched(200, URL, ResearchFixtures.page(ResearchFixtures.CIRS_78)));
        when(hypothesisProvider.propose(anyString(), any(), anyInt())).thenReturn(List.of(
                new Hypothesis(new LegislationReference("CIRS", 78, null), "deduções à coleta")));
        ExtractedProposition anchored = new ExtractedProposition(PropositionKind.RULE,
                "As deduções à coleta previstas no n.º 1 do artigo 78.º do CIRS aplicam-se apenas a sujeitos passivos "
                        + "residentes em território português.", "E1", ANCHOR, "IRS", "Residentes",
                List.of(new ExtractedCondition(ConditionKind.CONDITION, "O sujeito passivo é residente", ANCHOR)));
        when(extractor.extract(anyString(), any())).thenReturn(new Extraction(List.of(anchored), List.of()));
        when(modelClient.complete(any())).thenReturn(new AIResponse("stub", "stub",
                "Segundo o artigo 78.º do CIRS, as deduções aplicam-se apenas aos sujeitos passivos residentes em "
                        + "território português. Recomenda-se a confirmação por profissional qualificado.", 0, 0, 0));

        ResearchInvestigation i = service.investigate(org, UUID.randomUUID(), "As deduções à coleta aplicam-se a não residentes?");
        Trace t = traceService.trace(org, i.getId());

        // Decisão anterior: âncora literal e números bastam (sem segunda barreira).
        assertThat(t.propositions()).singleElement().extracting(p -> p.getStatus()).isEqualTo(PropositionStatus.CANDIDATE);
        assertThat(t.propositions().get(0).getStatusReason()).isEqualTo("Âncora literal verificada na evidência; não promovida (B4 bloqueada)");
        // Nenhum efeito novo persistido.
        assertThat(t.entailmentChecks()).isEmpty();
        assertThat(entailmentRepository.findByInvestigationIdOrderByCreatedAtAsc(i.getId())).isEmpty();
        assertThat(t.steps()).noneMatch(s -> s.getPhase() == ResearchPhase.ENTAILMENT
                || s.getOrigin() == StepOrigin.VERIFICATION || s.getOrigin() == StepOrigin.MODEL);
        assertThat(auditEventRepository.findAll()).filteredOn(a -> a.getAction() == AuditAction.KNOWLEDGE_PROPOSITION_RECORDED
                && t.propositions().get(0).getId().equals(a.getEntityId()))
                .singleElement().satisfies(a -> assertThat(a.getMetadata()).doesNotContain("entailment="));
        verifyNoInteractions(validator);
        verify(supportVerifier, never()).check(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }
}
