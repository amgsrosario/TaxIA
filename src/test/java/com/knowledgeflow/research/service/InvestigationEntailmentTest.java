package com.knowledgeflow.research.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.documented.AnswerType;
import com.knowledgeflow.ai.spend.MeteredModelClient;
import com.knowledgeflow.research.ResearchFixtures;
import com.knowledgeflow.research.entity.EntailmentCheck;
import com.knowledgeflow.research.entity.KnowledgeProposition;
import com.knowledgeflow.research.entity.ResearchInvestigation;
import com.knowledgeflow.research.enums.ConditionKind;
import com.knowledgeflow.research.enums.EntailmentDecision;
import com.knowledgeflow.research.enums.PropositionKind;
import com.knowledgeflow.research.enums.PropositionStatus;
import com.knowledgeflow.research.enums.ResearchPhase;
import com.knowledgeflow.research.enums.StepOrigin;
import com.knowledgeflow.research.service.CorpusCoverageChecker.Coverage;
import com.knowledgeflow.research.service.DiscoveryHypothesisProvider.Hypothesis;
import com.knowledgeflow.research.service.EntailmentValidator.Assessment;
import com.knowledgeflow.research.service.EntailmentValidator.Dimension;
import com.knowledgeflow.research.service.EntailmentValidator.DimensionStatus;
import com.knowledgeflow.research.service.EntailmentValidator.Verdict;
import com.knowledgeflow.research.service.InvestigationTraceService.Trace;
import com.knowledgeflow.research.service.PropositionExtractor.ExtractedCondition;
import com.knowledgeflow.research.service.PropositionExtractor.ExtractedProposition;
import com.knowledgeflow.research.service.PropositionExtractor.Extraction;
import com.knowledgeflow.research.source.LegislationReference;
import com.knowledgeflow.research.source.OfficialLegislationCatalog;
import com.knowledgeflow.research.source.OfficialSourceClient;
import com.knowledgeflow.research.source.OfficialSourceClient.FetchedPage;
import com.knowledgeflow.research.source.ResearchProperties;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADR-010-A, A4: integração da segunda barreira com ela <b>ligada</b> (delimitação v3, A.3/A.4/A.6). O validador e
 * o {@link SupportVerifier} são substituídos para controlar resultados (testados no A1/A2); as decisões locais, o
 * {@link EntailmentGate}, o registo e a política são os reais. Nunca há chamada a um fornecedor real.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {"knowledgeflow.research.enabled=true", "knowledgeflow.research.entailment.enabled=true"})
class InvestigationEntailmentTest {

    static final String BASE = "https://info.portaldasfinancas.gov.pt/pt/informacao_fiscal/codigos_tributarios";
    static final String CIRS_78_URL = BASE + "/cirs_rep/Pages/irs78.aspx";
    static final String ANCHOR = "aplicam-se apenas aos sujeitos passivos residentes em território português";
    static final String STATEMENT_A = "As deduções à coleta previstas no n.º 1 do artigo 78.º do CIRS aplicam-se "
            + "apenas a sujeitos passivos residentes em território português.";
    static final String STATEMENT_B = "As deduções à coleta do artigo 78.º do CIRS aplicam-se apenas aos sujeitos "
            + "passivos residentes em território português, em qualquer caso.";

    @Autowired InvestigationService service;
    @Autowired InvestigationTraceService traceService;
    @Autowired ResearchProperties properties;

    @MockBean OfficialSourceClient sourceClient;
    @MockBean CorpusCoverageChecker corpusChecker;
    @MockBean DiscoveryHypothesisProvider hypothesisProvider;
    @MockBean PropositionExtractor extractor;
    @MockBean MeteredModelClient modelClient;
    @MockBean EntailmentValidator validator;
    @MockBean SupportVerifier supportVerifier;
    @SpyBean OfficialLegislationCatalog catalog;

    UUID org;
    int savedMaxChecks;

    @BeforeEach
    void setUp() {
        org = UUID.randomUUID();
        savedMaxChecks = properties.getEntailment().getMaxChecks();
        when(corpusChecker.check(any(), anyString()))
                .thenReturn(new CorpusCoverageChecker.Result(Coverage.NO_RELEVANT_QA, List.of(), "Sem Q&A relevante"));
        when(sourceClient.fetch(CIRS_78_URL)).thenReturn(FetchedPage.fetched(200, CIRS_78_URL,
                ResearchFixtures.page(ResearchFixtures.CIRS_78)));
        when(hypothesisProvider.propose(anyString(), any(), anyInt())).thenReturn(List.of(
                new Hypothesis(new LegislationReference("CIRS", 78, null), "deduções à coleta")));
        when(validator.isAvailable()).thenReturn(true);
        SupportVerifier.Result ok = new SupportVerifier.Result(true, 1.0, List.of());
        when(supportVerifier.check(anyString(), anyString(), anyString(), anyString(), anyString(), anyList())).thenReturn(ok);
        when(supportVerifier.checkCondition(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(ok);
    }

    @AfterEach
    void restore() {
        properties.getEntailment().setMaxChecks(savedMaxChecks);
    }

    private static ExtractedProposition proposition(String statement) {
        return new ExtractedProposition(PropositionKind.RULE, statement, "E1", ANCHOR, "IRS — deduções à coleta",
                "Sujeitos passivos residentes", List.of(new ExtractedCondition(ConditionKind.CONDITION,
                        "O sujeito passivo é residente em território português", ANCHOR)));
    }

    private static Assessment assessment(Verdict verdict) {
        Map<Dimension, DimensionStatus> dims = new EnumMap<>(Dimension.class);
        for (Dimension d : Dimension.values()) {
            dims.put(d, verdict == Verdict.SUPORTA ? DimensionStatus.CONFORME : DimensionStatus.NAO_CONFORME);
        }
        return new Assessment(verdict, dims, Map.of(), "justificação", ANCHOR, null, null);
    }

    private Trace run(ExtractedProposition... propositions) {
        when(extractor.extract(anyString(), any())).thenReturn(new Extraction(List.of(propositions), List.of()));
        when(modelClient.complete(any())).thenReturn(new AIResponse("stub", "stub",
                "Segundo o artigo 78.º do CIRS, as deduções aplicam-se apenas aos sujeitos passivos residentes em "
                        + "território português. Recomenda-se a confirmação por profissional qualificado.", 0, 0, 0));
        ResearchInvestigation i = service.investigate(org, UUID.randomUUID(), "As deduções à coleta aplicam-se a não residentes?");
        return traceService.trace(org, i.getId());
    }

    private static List<String> reasons(Trace t) {
        return t.entailmentChecks().stream().map(EntailmentCheck::getDecisionReason).toList();
    }

    @Test
    @DisplayName("SUPORTA com as sete dimensões conformes → demonstrada; linha na tabela e passo VERIFICATION")
    void demonstratedPropositionIsUsable() {
        when(validator.assess(any())).thenReturn(assessment(Verdict.SUPORTA));
        Trace t = run(proposition(STATEMENT_A));
        assertThat(t.entailmentChecks()).singleElement().satisfies(c -> {
            assertThat(c.getDecision()).as(c.getDecisionReason()).isEqualTo(EntailmentDecision.DEMONSTRATED);
            assertThat(c.getVerdict()).isEqualTo(Verdict.SUPORTA);
        });
        assertThat(t.propositions()).singleElement().extracting(KnowledgeProposition::getStatus).isEqualTo(PropositionStatus.CANDIDATE);
        assertThat(t.steps()).anyMatch(s -> s.getPhase() == ResearchPhase.ENTAILMENT && s.getOrigin() == StepOrigin.VERIFICATION);
        assertThat(t.investigation().getAnswerType()).isNotEqualTo(AnswerType.RESPOSTA_LIMITE);
    }

    @Test
    @DisplayName("Todas não demonstradas → resposta-limite com a razão da segunda barreira; o compositor não é chamado")
    void allNotDemonstratedGivesLimitAnswer() {
        when(validator.assess(any())).thenReturn(assessment(Verdict.NAO_SUPORTA));
        Trace t = run(proposition(STATEMENT_A));
        assertThat(t.entailmentChecks()).singleElement().extracting(EntailmentCheck::getDecision)
                .isEqualTo(EntailmentDecision.NOT_DEMONSTRATED);
        assertThat(t.propositions()).allMatch(p -> p.getStatus() == PropositionStatus.REJECTED);
        assertThat(t.investigation().getAnswerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(t.investigation().getDecisionReasons()).contains("implicação jurídica não demonstrada pela segunda barreira");
        verify(modelClient, never()).complete(any());
    }

    @Test
    @DisplayName("max-checks inválido (0) → decisão local «não demonstrada», sem chamada ao validador, registada no rasto")
    void invalidMaxChecksIsALocalDecision() {
        properties.getEntailment().setMaxChecks(0);
        Trace t = run(proposition(STATEMENT_A));
        verify(validator, never()).assess(any());
        assertThat(reasons(t)).singleElement().asString().contains("max-checks inválido");
        assertThat(t.entailmentChecks()).allMatch(c -> c.getVerdict() == null && !c.isCitationVerified());
    }

    @Test
    @DisplayName("Limite de validações atingido (max-checks 1, duas proposições) → uma chamada; a segunda é decisão local")
    void exhaustedLimitIsALocalDecision() {
        properties.getEntailment().setMaxChecks(1);
        when(validator.assess(any())).thenReturn(assessment(Verdict.SUPORTA));
        Trace t = run(proposition(STATEMENT_A), proposition(STATEMENT_B));
        verify(validator, times(1)).assess(any());
        assertThat(reasons(t)).hasSize(2).anySatisfy(r -> assertThat(r).contains("limite de validações"));
    }

    @Test
    @DisplayName("Diploma sem identificação verificada (CIRC) → «não demonstrada» antes do SupportVerifier e do validador")
    void missingIdentificationIsRejectedBeforeSupportAndValidator() {
        doReturn(Optional.empty()).when(catalog).identification("CIRS");
        Trace t = run(proposition(STATEMENT_A));
        verify(validator, never()).assess(any());
        verify(supportVerifier, never()).check(anyString(), anyString(), anyString(), anyString(), anyString(), anyList());
        verify(supportVerifier, never()).checkCondition(anyString(), anyString(), anyString(), anyString(), anyString());
        assertThat(t.entailmentChecks()).singleElement().satisfies(c -> {
            assertThat(c.getDecision()).isEqualTo(EntailmentDecision.NOT_DEMONSTRATED);
            assertThat(c.getVerdict()).isNull();
            assertThat(c.getDecisionReason()).contains("diploma sem identificação verificada no catálogo: CIRS");
        });
        assertThat(t.investigation().getAnswerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
    }

    @Test
    @DisplayName("Proposições mistas: só a demonstrada chega ao compositor como utilizável")
    void mixedPropositionsOnlyDemonstratedReachTheComposer() {
        when(validator.assess(any())).thenReturn(assessment(Verdict.SUPORTA), assessment(Verdict.NAO_SUPORTA));
        Trace t = run(proposition(STATEMENT_A), proposition(STATEMENT_B));
        assertThat(t.propositions()).extracting(KnowledgeProposition::getStatus)
                .containsExactlyInAnyOrder(PropositionStatus.CANDIDATE, PropositionStatus.REJECTED);
        ArgumentCaptor<AIRequest> request = ArgumentCaptor.forClass(AIRequest.class);
        verify(modelClient).complete(request.capture());
        assertThat(request.getValue().systemPrompt()).contains(STATEMENT_A).doesNotContain(STATEMENT_B);
    }

    @Test
    @DisplayName("LIMITAÇÃO DOCUMENTADA (A.4): com evidência partilhada, o compositor actual pode reintroduzir a proposição rejeitada")
    void compositionLimitationIsDemonstrated() {
        when(validator.assess(any())).thenReturn(assessment(Verdict.SUPORTA), assessment(Verdict.NAO_SUPORTA));
        when(extractor.extract(anyString(), any())).thenReturn(new Extraction(
                List.of(proposition(STATEMENT_A), proposition(STATEMENT_B)), List.of()));
        // O modelo de redacção recebe o texto integral da evidência e pode afirmar o que a barreira rejeitou.
        String reintroduced = "Segundo o artigo 78.º do CIRS, " + STATEMENT_B.substring(0, 1).toLowerCase()
                + STATEMENT_B.substring(1) + " Recomenda-se a confirmação por profissional qualificado.";
        when(modelClient.complete(any())).thenReturn(new AIResponse("stub", "stub", reintroduced, 0, 0, 0));
        ResearchInvestigation i = service.investigate(org, UUID.randomUUID(), "As deduções à coleta aplicam-se a não residentes?");
        Trace t = traceService.trace(org, i.getId());
        assertThat(t.propositions()).anyMatch(p -> p.getStatement().equals(STATEMENT_B) && p.getStatus() == PropositionStatus.REJECTED);
        // Registo honesto da limitação: o texto final é decidido pelo grounding contra a evidência, não pela barreira.
        // Se o grounding o aceitar, a resposta contém a afirmação rejeitada; é por isso que a composição restrita é
        // pré-requisito de activação do modo integrado (guarda do L7).
        // Verificado: a resposta não é limite e contém a afirmação rejeitada («em qualquer caso»).
        assertThat(t.investigation().getAnswerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(t.investigation().getAnswerText()).contains("em qualquer caso");
    }

    @Test
    @DisplayName("Validador indisponível → decisão local «não demonstrada», sem chamada")
    void unavailableValidatorIsALocalDecision() {
        when(validator.isAvailable()).thenReturn(false);
        Trace t = run(proposition(STATEMENT_A));
        verify(validator, never()).assess(any());
        assertThat(reasons(t)).singleElement().asString().contains("indisponível");
    }

    @Test
    @DisplayName("Ocorrência da âncora não identificável → decisão local «não demonstrada», sem chamada")
    void unidentifiableOccurrenceIsALocalDecision() {
        try (MockedStatic<SupportVerifier> sv = mockStatic(SupportVerifier.class, CALLS_REAL_METHODS)) {
            sv.when(() -> SupportVerifier.occurrenceIdentified(anyString(), anyString())).thenReturn(false);
            Trace t = run(proposition(STATEMENT_A));
            verify(validator, never()).assess(any());
            assertThat(reasons(t)).singleElement().asString().contains("ocorrência da âncora não identificável");
        }
    }

    @Test
    @DisplayName("Contexto normativo perdido → decisão local «não demonstrada», sem chamada")
    void lostNormativeContextIsALocalDecision() {
        try (MockedStatic<NormativeContextGuard> g = mockStatic(NormativeContextGuard.class, CALLS_REAL_METHODS)) {
            g.when(() -> NormativeContextGuard.violation(anyString(), anyList(), any()))
                    .thenReturn(Optional.of("contexto normativo perdido (teste)"));
            Trace t = run(proposition(STATEMENT_A));
            verify(validator, never()).assess(any());
            assertThat(reasons(t)).singleElement().asString().contains("contexto normativo perdido (teste)");
        }
    }
}
