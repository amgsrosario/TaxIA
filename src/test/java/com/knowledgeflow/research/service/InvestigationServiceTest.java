package com.knowledgeflow.research.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.AIService;
import com.knowledgeflow.ai.documented.AnswerType;
import com.knowledgeflow.ai.documented.ParecerRequirement;
import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.repository.AuditEventRepository;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.research.entity.CriticalMemoryEntry;
import com.knowledgeflow.research.entity.KnowledgeProposition;
import com.knowledgeflow.research.entity.ResearchInvestigation;
import com.knowledgeflow.research.enums.ConditionKind;
import com.knowledgeflow.research.enums.CriticalClassification;
import com.knowledgeflow.research.enums.CriticalHistoryEvent;
import com.knowledgeflow.research.enums.CriticalStatus;
import com.knowledgeflow.research.enums.EvidenceClassification;
import com.knowledgeflow.research.enums.InvestigationStatus;
import com.knowledgeflow.research.enums.PropositionKind;
import com.knowledgeflow.research.enums.PropositionStatus;
import com.knowledgeflow.research.enums.ResearchPhase;
import com.knowledgeflow.research.enums.StepOrigin;
import com.knowledgeflow.research.enums.StepOutcome;
import com.knowledgeflow.research.enums.ValidityStatus;
import com.knowledgeflow.research.service.CorpusCoverageChecker.Coverage;
import com.knowledgeflow.research.service.DiscoveryHypothesisProvider.Hypothesis;
import com.knowledgeflow.research.service.InvestigationTraceService.Trace;
import com.knowledgeflow.research.service.PropositionExtractor.ExtractedCondition;
import com.knowledgeflow.research.service.PropositionExtractor.ExtractedConflict;
import com.knowledgeflow.research.service.PropositionExtractor.ExtractedProposition;
import com.knowledgeflow.research.service.PropositionExtractor.Extraction;
import com.knowledgeflow.research.ResearchFixtures;
import com.knowledgeflow.research.source.LegislationReference;
import com.knowledgeflow.research.source.OfficialSourceClient;
import com.knowledgeflow.research.source.OfficialSourceClient.FetchedPage;
import com.knowledgeflow.research.source.ResearchProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

/**
 * Percurso completo Research → Evidence → Critical Memory → Structured Knowledge numa base H2 em
 * memória (descartável). A fonte oficial devolve páginas reais gravadas (fixtures) e o modelo é
 * substituído por respostas controladas; nada sai para a rede.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "knowledgeflow.research.enabled=true")
class InvestigationServiceTest {

    static final String BASE = "https://info.portaldasfinancas.gov.pt/pt/informacao_fiscal/codigos_tributarios";
    static final String CIRS_78_URL = BASE + "/cirs_rep/Pages/irs78.aspx";
    static final String CIRS_999_URL = BASE + "/cirs_rep/Pages/irs999.aspx";
    static final String CIVA_9_URL = BASE + "/civa_rep/Pages/iva9.aspx";

    static final String RESIDENCE_ANCHOR = "aplicam-se apenas aos sujeitos passivos residentes em território português";
    static final String STATEMENT = "As deduções à coleta previstas no n.º 1 do artigo 78.º do CIRS aplicam-se "
            + "apenas a sujeitos passivos residentes em território português.";
    static final String GOOD_ANSWER = "Segundo o artigo 78.º do CIRS, as deduções previstas no n.º 1 aplicam-se apenas "
            + "aos sujeitos passivos residentes em território português. Pressuposto a confirmar: a residência. "
            + "Recomenda-se a confirmação por profissional qualificado.";

    @Autowired InvestigationService service;
    @Autowired InvestigationTraceService traceService;
    @Autowired CriticalMemoryService criticalMemoryService;
    @Autowired ResearchProperties properties;
    @Autowired AuditEventRepository auditEventRepository;

    @MockBean OfficialSourceClient sourceClient;
    @MockBean CorpusCoverageChecker corpusChecker;
    @MockBean DiscoveryHypothesisProvider hypothesisProvider;
    @MockBean PropositionExtractor extractor;
    @MockBean AIService aiService;

    UUID org;
    UUID user;

    @BeforeEach
    void setUp() {
        org = UUID.randomUUID();
        user = UUID.randomUUID();
        when(corpusChecker.check(any(), anyString()))
                .thenReturn(new CorpusCoverageChecker.Result(Coverage.NO_RELEVANT_QA, List.of(), "Sem Q&A relevante"));
        when(sourceClient.fetch(CIRS_78_URL)).thenReturn(FetchedPage.fetched(200, CIRS_78_URL,
                ResearchFixtures.page(ResearchFixtures.CIRS_78)));
        when(sourceClient.fetch(CIVA_9_URL)).thenReturn(FetchedPage.fetched(200, CIVA_9_URL,
                ResearchFixtures.page(ResearchFixtures.CIVA_9)));
        when(sourceClient.fetch(CIRS_999_URL)).thenReturn(FetchedPage.notFound(404, CIRS_999_URL));
        when(hypothesisProvider.propose(anyString(), any(), anyInt())).thenReturn(List.of(
                new Hypothesis(new LegislationReference("CIRS", 78, null), "deduções à coleta"),
                new Hypothesis(new LegislationReference("CIRS", 999, null), "hipótese errada")));
        when(aiService.complete(any())).thenReturn(new AIResponse("stub", "stub", GOOD_ANSWER, 0, 0, 0));
    }

    private static ExtractedProposition anchored() {
        return new ExtractedProposition(PropositionKind.RULE, STATEMENT, "E1", RESIDENCE_ANCHOR, "IRS — deduções à coleta",
                "Sujeitos passivos residentes", List.of(
                        new ExtractedCondition(ConditionKind.CONDITION, "O sujeito passivo é residente em território português",
                                RESIDENCE_ANCHOR),
                        new ExtractedCondition(ConditionKind.EXCEPTION, "Não residentes da União Europeia podem deduzir",
                                "não residentes da União Europeia podem deduzir")));
    }

    private static ExtractedProposition unanchored() {
        return new ExtractedProposition(PropositionKind.PROPOSITION, "As despesas de ginásio são dedutíveis até 250 euros.",
                "E1", "as despesas de ginásio são dedutíveis à coleta", null, null, List.of());
    }

    private Trace run(String question) {
        ResearchInvestigation i = service.investigate(org, user, question);
        return traceService.trace(org, i.getId());
    }

    @Test
    @DisplayName("Pergunta inédita → investigação, evidência oficial, memória crítica, conhecimento estruturado e resposta com limitações")
    void fullPath() {
        when(extractor.extract(anyString(), any())).thenReturn(new Extraction(List.of(anchored(), unanchored()), List.of()));

        Trace t = run("Um não residente pode fazer deduções à coleta no IRS?");
        ResearchInvestigation inv = t.investigation();

        // Resposta: mínimo do Aditamento §2
        assertThat(inv.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(inv.getAnswerType()).isEqualTo(AnswerType.CONSULTA_DOCUMENTADA_COM_LIMITACOES);
        assertThat(inv.getParecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
        assertThat(inv.getAnswerText()).isEqualTo(GOOD_ANSWER);
        assertThat(inv.getLimitations()).contains("NÃO PROMOVIDA").contains("artigo 78.º do CIRS");
        assertThat(inv.getAssumptions()).contains("Confirmar a condição: O sujeito passivo é residente")
                .doesNotContain("União Europeia");

        // Research: descoberta, consulta e confirmação distinguidas
        assertThat(t.steps()).extracting(s -> s.getPhase() + "/" + s.getOrigin() + "/" + s.getOutcome()).containsExactly(
                "CORPUS_CHECK/CORPUS/NO_RELEVANT_QA",
                "DISCOVERY/MODEL_HYPOTHESIS/PROPOSED",
                "DISCOVERY/MODEL_HYPOTHESIS/PROPOSED",
                "CONSULTATION/OFFICIAL_SOURCE/FETCHED",
                "CONFIRMATION/OFFICIAL_SOURCE/CONFIRMED",
                "CONSULTATION/OFFICIAL_SOURCE/NOT_FOUND");

        // Evidence: origem, contexto, identificação, vigência, não promovida
        assertThat(t.evidence()).singleElement().satisfies(e -> {
            assertThat(e.getClassification()).isEqualTo(EvidenceClassification.ACCEPTED);
            assertThat(e.getUrl()).isEqualTo(CIRS_78_URL);
            assertThat(e.getReferenceKey()).isEqualTo("CIRS:78");
            assertThat(e.getContentSha256()).hasSize(64);
            assertThat(e.getValidityStatus()).isEqualTo(ValidityStatus.CONSOLIDATED_AT_RETRIEVAL);
            assertThat(e.getPromotionStatus()).isEqualTo("NOT_PROMOTED");
            assertThat(e.getConsultationStepId()).isEqualTo(t.steps().get(3).getId());
        });

        // Structured knowledge: proposição ancorada com condição; sem âncora → REJECTED
        KnowledgeProposition good = t.propositions().get(0);
        assertThat(good.getStatus()).isEqualTo(PropositionStatus.CANDIDATE);
        assertThat(good.isUsableAsFoundation()).isTrue();
        assertThat(good.getSupports()).singleElement().satisfies(s -> {
            assertThat(s.getEvidenceId()).isEqualTo(t.evidence().get(0).getId());
            assertThat(s.isAnchored()).isTrue();
        });
        assertThat(good.getConditions()).extracting(KnowledgeProposition.Condition::isAnchored).containsExactly(true, false);
        assertThat(t.propositions().get(1).getStatus()).isEqualTo(PropositionStatus.REJECTED);

        // Critical memory: hipótese desmentida, revogação, extracções sem âncora
        assertThat(t.criticalMemory()).extracting(CriticalMemoryEntry::getClassification).contains(
                CriticalClassification.ERRONEOUS, CriticalClassification.SUPERSEDED, CriticalClassification.REJECTED);
        assertThat(t.criticalMemory()).anySatisfy(c -> {
            assertThat(c.getClaim()).contains("ginásio");
            assertThat(c.getPropositionId()).isEqualTo(t.propositions().get(1).getId());
            assertThat(c.getEvidenceId()).isEqualTo(t.evidence().get(0).getId());
            assertThat(c.getProvenance()).contains(CIRS_78_URL);
        });
        assertThat(t.criticalMemory()).anySatisfy(c -> {
            assertThat(c.getClassification()).isEqualTo(CriticalClassification.ERRONEOUS);
            assertThat(c.getReferenceKey()).isEqualTo("CIRS:999");
            assertThat(c.getReason()).contains("HTTP 404");
        });

        // O conteúdo da memória crítica nunca chega ao prompt da resposta
        ArgumentCaptor<AIRequest> prompt = ArgumentCaptor.forClass(AIRequest.class);
        verify(aiService).complete(prompt.capture());
        assertThat(prompt.getValue().systemPrompt())
                .contains("EVIDÊNCIA OFICIAL NÃO PROMOVIDA")
                .contains(STATEMENT)
                .doesNotContain("ginásio")
                .doesNotContain("União Europeia")
                .doesNotContain("999");

        assertThat(auditEventRepository.findAll()).extracting(a -> a.getAction())
                .contains(AuditAction.RESEARCH_INVESTIGATION_STARTED, AuditAction.RESEARCH_EVIDENCE_RECORDED,
                        AuditAction.KNOWLEDGE_PROPOSITION_RECORDED, AuditAction.CRITICAL_MEMORY_RECORDED,
                        AuditAction.RESEARCH_INVESTIGATION_COMPLETED);
    }

    @Test
    @DisplayName("Memória crítica é recuperada: hipótese já desmentida não é consultada de novo (recorrência)")
    void refutedHypothesisIsNotRepeated() {
        when(extractor.extract(anyString(), any())).thenReturn(new Extraction(List.of(anchored()), List.of()));
        run("Um não residente pode fazer deduções à coleta no IRS?");

        Trace second = run("Que deduções à coleta existem no IRS?");

        assertThat(second.steps()).anySatisfy(s -> {
            assertThat(s.getPhase()).isEqualTo(ResearchPhase.DISCOVERY);
            assertThat(s.getOrigin()).isEqualTo(StepOrigin.CRITICAL_MEMORY);
            assertThat(s.getOutcome()).isEqualTo(StepOutcome.SKIPPED);
            assertThat(s.getTarget()).isEqualTo("artigo 999.º do CIRS");
        });
        verify(sourceClient, times(1)).fetch(CIRS_999_URL);
        CriticalMemoryEntry refuted = criticalMemoryService.findByReferenceKeys(org, List.of("CIRS:999")).get(0);
        assertThat(refuted.getOccurrences()).isEqualTo(2);
        assertThat(criticalMemoryService.history(org, refuted.getId())).extracting(h -> h.getEvent())
                .containsExactly(CriticalHistoryEvent.CREATED, CriticalHistoryEvent.RECURRED);
    }

    @Test
    @DisplayName("Conflito entre proposições → quarentena, CONTRADICTORY com relação ao contraditor, RESPOSTA_LIMITE + REQUIRED")
    void conflictLeadsToBoundaryAnswer() {
        ExtractedProposition other = new ExtractedProposition(PropositionKind.RULE,
                "As deduções à coleta dependem de documentos comunicados à Autoridade Tributária e Aduaneira.", "E1",
                "constarem de documentos comunicados pelos emitentes à Autoridade Tributária e Aduaneira",
                null, null, List.of());
        when(extractor.extract(anyString(), any())).thenReturn(
                new Extraction(List.of(anchored(), other), List.of(new ExtractedConflict(0, 1, "âmbito incompatível"))));

        Trace t = run("Um não residente pode fazer deduções à coleta no IRS?");

        assertThat(t.investigation().getAnswerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(t.investigation().getParecerRequirement()).isEqualTo(ParecerRequirement.REQUIRED);
        assertThat(t.propositions()).extracting(KnowledgeProposition::getStatus)
                .containsOnly(PropositionStatus.QUARANTINED);
        assertThat(t.relations()).singleElement().satisfies(r -> assertThat(r.isResolved()).isFalse());
        List<CriticalMemoryEntry> contradictory = t.criticalMemory().stream()
                .filter(c -> c.getClassification() == CriticalClassification.CONTRADICTORY).toList();
        assertThat(contradictory).hasSize(2);
        assertThat(contradictory).allSatisfy(c -> assertThat(c.getContradictedByPropositionId()).isNotNull());
        verify(aiService, never()).complete(any());
    }

    @Test
    @DisplayName("Resposta com valor ausente da evidência é rejeitada pelo grounding → RESPOSTA_LIMITE e memória crítica")
    void groundingRejectionIsRecorded() {
        when(extractor.extract(anyString(), any())).thenReturn(new Extraction(List.of(anchored()), List.of()));
        when(aiService.complete(any())).thenReturn(new AIResponse("stub", "stub",
                "Nos termos do artigo 78.º do CIRS, o limite é de 2 500 euros por agregado.", 0, 0, 0));

        Trace t = run("Um não residente pode fazer deduções à coleta no IRS?");

        assertThat(t.investigation().getAnswerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(t.investigation().getAnswerText()).startsWith("Não é possível apresentar uma conclusão segura")
                .doesNotContain("2 500");
        assertThat(t.criticalMemory()).anySatisfy(c -> {
            assertThat(c.getClassification()).isEqualTo(CriticalClassification.REJECTED);
            assertThat(c.getClaim()).contains("2 500 euros");
            assertThat(c.getReason()).contains("grounding");
        });
    }

    @Test
    @DisplayName("Vigência incerta na evidência que sustenta a conclusão → RESPOSTA_LIMITE + REQUIRED")
    void uncertainValidityLeadsToBoundaryAnswer() {
        String future = """
                <html><body><div id="BODYCONTENT"><span class="titulos">Artigo 78.º<br>Deduções à coleta</span>
                <p>5 - As deduções previstas no n.º 1 aplicam-se apenas aos sujeitos passivos residentes em território português.</p>
                <p>(Redação da Lei n.º 1/2099, de 1 de janeiro)</p></div></body></html>""";
        when(sourceClient.fetch(CIRS_78_URL)).thenReturn(FetchedPage.fetched(200, CIRS_78_URL, future));
        when(extractor.extract(anyString(), any())).thenReturn(new Extraction(List.of(anchored()), List.of()));

        Trace t = run("Um não residente pode fazer deduções à coleta no IRS?");

        assertThat(t.evidence().get(0).getValidityStatus()).isEqualTo(ValidityStatus.UNCERTAIN);
        assertThat(t.investigation().getAnswerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(t.investigation().getParecerRequirement()).isEqualTo(ParecerRequirement.REQUIRED);
        verify(aiService, never()).complete(any());
    }

    @Test
    @DisplayName("Sem evidência confirmada → RESPOSTA_LIMITE + SUGGESTED, sem estruturação nem resposta do modelo")
    void noEvidenceIsBoundaryAnswer() {
        when(hypothesisProvider.propose(anyString(), any(), anyInt())).thenReturn(List.of(
                new Hypothesis(new LegislationReference("CIRS", 999, null), "errada")));

        Trace t = run("Pergunta sem fonte confirmável?");

        assertThat(t.evidence()).isEmpty();
        assertThat(t.investigation().getAnswerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(t.investigation().getParecerRequirement()).isEqualTo(ParecerRequirement.SUGGESTED);
        verify(extractor, never()).extract(anyString(), any());
        verify(aiService, never()).complete(any());
    }

    @Test
    @DisplayName("Resposta da fonte recusada por tamanho → passo FAILED auditado, nenhuma evidência, RESPOSTA_LIMITE")
    void oversizedResponseCreatesNoEvidence() {
        when(sourceClient.fetch(CIRS_78_URL)).thenReturn(FetchedPage.failed(200, CIRS_78_URL,
                "Resposta recusada: corpo excede o limite de 2000000 bytes"));

        Trace t = run("Um não residente pode fazer deduções à coleta no IRS?");

        assertThat(t.evidence()).isEmpty();
        assertThat(t.propositions()).isEmpty();
        assertThat(t.steps()).anySatisfy(s -> {
            assertThat(s.getPhase()).isEqualTo(ResearchPhase.CONSULTATION);
            assertThat(s.getOutcome()).isEqualTo(StepOutcome.FAILED);
            assertThat(s.getDetail()).contains("excede o limite");
        });
        assertThat(t.investigation().getAnswerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
        assertThat(auditEventRepository.findAll()).anySatisfy(a -> {
            assertThat(a.getAction()).isEqualTo(AuditAction.RESEARCH_CONSULTATION_REJECTED);
            assertThat(a.getEntityId()).isEqualTo(t.investigation().getId());
            assertThat(a.getMetadata()).contains("outcome=FAILED").contains("excede o limite");
        });
        verify(extractor, never()).extract(anyString(), any());
    }

    @Test
    @DisplayName("Referência explícita da pergunta é descoberta sem o modelo e confirmada na fonte")
    void explicitReferenceFromQuestion() {
        when(hypothesisProvider.propose(anyString(), any(), anyInt())).thenReturn(List.of());
        when(extractor.extract(anyString(), any())).thenReturn(Extraction.empty());

        Trace t = run("Nos termos do artigo 9.º do CIVA, as explicações estão isentas?");

        assertThat(t.steps()).anySatisfy(s -> {
            assertThat(s.getOrigin()).isEqualTo(StepOrigin.QUESTION_REFERENCE);
            assertThat(s.getTarget()).isEqualTo("artigo 9.º do CIVA");
        });
        assertThat(t.evidence()).singleElement().satisfies(e -> assertThat(e.getUrl()).isEqualTo(CIVA_9_URL));
        // Sem proposição ancorada → insuficiência de fundamento estruturado
        assertThat(t.investigation().getAnswerType()).isEqualTo(AnswerType.RESPOSTA_LIMITE);
    }

    @Test
    @DisplayName("Q&A publicada relevante → a investigação não é aberta e nada é consultado")
    void relevantQaPreventsInvestigation() {
        when(corpusChecker.check(any(), anyString())).thenReturn(
                new CorpusCoverageChecker.Result(Coverage.RELEVANT_QA_FOUND, List.of("Q&A"), "relevante"));

        ResearchInvestigation i = service.investigate(org, user, "Pergunta coberta?");

        assertThat(i.getStatus()).isEqualTo(InvestigationStatus.NOT_STARTED);
        verify(sourceClient, never()).fetch(anyString());
        verify(hypothesisProvider, never()).propose(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("Módulo desligado → recusa sem qualquer consulta")
    void disabledModuleRefuses() {
        properties.setEnabled(false);
        try {
            assertThatThrownBy(() -> service.investigate(org, user, "Pergunta?")).isInstanceOf(BusinessException.class);
            verify(sourceClient, never()).fetch(anyString());
        } finally {
            properties.setEnabled(true);
        }
    }

    @Test
    @DisplayName("Reavaliação humana muda só o estado, com histórico; a afirmação mantém-se")
    void humanReevaluationKeepsClaim() {
        when(extractor.extract(anyString(), any())).thenReturn(new Extraction(List.of(unanchored()), List.of()));
        Trace t = run("Um não residente pode fazer deduções à coleta no IRS?");
        CriticalMemoryEntry entry = t.criticalMemory().stream()
                .filter(c -> c.getClaim().contains("ginásio")).findFirst().orElseThrow();

        CriticalMemoryEntry changed = criticalMemoryService.changeStatus(org, user, "curador@test", entry.getId(),
                CriticalStatus.UNDER_REEVALUATION, "Rever com nova fonte");

        assertThat(changed.getStatus()).isEqualTo(CriticalStatus.UNDER_REEVALUATION);
        assertThat(changed.getClaim()).isEqualTo(entry.getClaim());
        assertThat(changed.getClassification()).isEqualTo(entry.getClassification());
        assertThat(criticalMemoryService.history(org, entry.getId())).extracting(h -> h.getEvent())
                .containsExactly(CriticalHistoryEvent.CREATED, CriticalHistoryEvent.STATUS_CHANGED);
        assertThatThrownBy(() -> criticalMemoryService.changeStatus(org, user, "curador@test", entry.getId(),
                CriticalStatus.ACTIVE, " ")).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("Falha técnica no meio do percurso → FAILED, sem resposta")
    void technicalFailureIsRecorded() {
        when(extractor.extract(anyString(), any())).thenThrow(new IllegalStateException("boom"));

        Trace t = run("Um não residente pode fazer deduções à coleta no IRS?");

        assertThat(t.investigation().getStatus()).isEqualTo(InvestigationStatus.FAILED);
        assertThat(t.investigation().getAnswerType()).isNull();
        assertThat(t.investigation().getFailureReason()).contains("IllegalStateException");
        verify(aiService, never()).complete(any());
    }
}
