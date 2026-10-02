package com.knowledgeflow.ai.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.AIService;
import com.knowledgeflow.ai.documented.CuratedSourceResolver;
import com.knowledgeflow.ai.grounding.scope.ApplicabilityMarkerDetector;
import com.knowledgeflow.ai.grounding.scope.CandidateScopeLoader;
import com.knowledgeflow.ai.grounding.scope.FiscalScopeClassifier;
import com.knowledgeflow.ai.grounding.scope.FiscalScopeFilter;
import com.knowledgeflow.ai.grounding.scope.ScopeCompatibilityGate;
import com.knowledgeflow.ai.grounding.scope.ScopeGateProperties;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.repository.KnowledgeQaApplicabilityExclusionRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeQaExclusionMarkerRow;
import com.knowledgeflow.knowledge.repository.KnowledgeQaScopeRow;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import com.knowledgeflow.rag.RagSearchService.SourceKind;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Gate de contradição de âmbito (M4-SCOPE) dentro do pipeline: depois do filtro de relevância (0.88)
 * e antes do evaluator, do prompt, do validador e das fontes. Os scores são os medidos em M4-RECAL.
 */
@ExtendWith(MockitoExtension.class)
class GroundingScopeGateTest {

    private static final GroundingProperties PROPS = new GroundingProperties(true, 1, 1, 0.88, true, true);

    private static final UUID QA_2721 = UUID.fromString("00000000-0000-0000-0000-000000002721");
    private static final UUID QA_5795 = UUID.fromString("00000000-0000-0000-0000-000000005795");
    private static final UUID QA_5930 = UUID.fromString("00000000-0000-0000-0000-000000005930");
    private static final UUID QA_CIVA = UUID.fromString("00000000-0000-0000-0000-00000000c1fa");

    @Mock private AIService aiService;
    @Mock private KnowledgeQuestionAnswerRepository qaRepository;
    @Mock private KnowledgeQaApplicabilityExclusionRepository exclusionRepository;
    @Mock private KnowledgeSourceReferenceRepository sourceRepository;

    private GroundingService service;

    @BeforeEach
    void setUp() {
        FiscalScopeClassifier classifier = new FiscalScopeClassifier();
        FiscalScopeFilter filter = new FiscalScopeFilter(
                new CandidateScopeLoader(qaRepository, exclusionRepository, classifier), classifier, new ApplicabilityMarkerDetector(), new ScopeCompatibilityGate(), new ScopeGateProperties(true));
        service = new GroundingService(new ContextSufficiencyEvaluator(PROPS), new AnswerGroundingValidator(PROPS),
                new SafeResponseFactory(), aiService, PROPS, filter);
    }

    @Test
    void q45_irsDocumentRetention_doesNotGetTheVatSource_andBecomesRespostaLimite() {
        when(qaRepository.findScopeRowsByIdIn(any())).thenReturn(corpus());

        GroundedAIResponse result = service.process("Durante quanto tempo tenho de conservar os documentos de IRS?",
                null, List.of(qa("CIVA", 0.8918, QA_CIVA), qa("5795", 0.8434, QA_5795)));

        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
        assertThat(result.providerCalled()).isFalse();
        assertThat(result.sources()).isEmpty();
        verifyNoInteractions(aiService);
        // só o candidato que passou a relevância é carregado
        verify(qaRepository).findScopeRowsByIdIn(Set.of(QA_CIVA));
    }

    @Test
    void exactQuestion_keepsItsQa_andRejectedCandidatesNeverReachPromptOrSources() {
        when(qaRepository.findScopeRowsByIdIn(any())).thenReturn(corpus());
        when(aiService.complete(any())).thenReturn(answer("Resposta."));

        GroundedAIResponse result = service.process(
                "Um pensionista pode pedir retenção mensal quando cada pensão, isoladamente, corresponde a taxa de 0%?",
                null, List.of(qa("5795", 0.8915, QA_5795), qa("CIVA", 0.8901, QA_CIVA), qa("5930", 0.885, QA_5930)));

        assertThat(result.sources()).extracting(AnswerSource::sourceQaId).containsExactly(QA_5795);
        ArgumentCaptor<AIRequest> request = ArgumentCaptor.forClass(AIRequest.class);
        verify(aiService).complete(request.capture());
        assertThat(request.getValue().systemPrompt()).contains("[FONTE: 5795]")
                .doesNotContain("[FONTE: CIVA]", "[FONTE: 5930]");
    }

    @Test
    void rejectedCandidate_isNotInTheSourcesHandedToCuratedSourceResolution() {
        when(qaRepository.findScopeRowsByIdIn(any())).thenReturn(corpus());
        when(aiService.complete(any())).thenReturn(answer("Resposta."));

        GroundedAIResponse result = service.process("Qual é o prazo para optar pela tributação conjunta em AIMI?",
                null, List.of(qa("2721", 0.9206, QA_2721), qa("5930", 0.881, QA_5930)));
        new CuratedSourceResolver(sourceRepository).resolve(result.sources());

        assertThat(result.sources()).extracting(AnswerSource::sourceQaId).containsExactly(QA_2721);
        verify(sourceRepository).findRowsByQuestionAnswerIdIn(Set.of(QA_2721));
    }

    @Test
    void documentCandidate_isKept_whileContradictingQaIsRejected() {
        when(qaRepository.findScopeRowsByIdIn(any())).thenReturn(corpus());
        when(aiService.complete(any())).thenReturn(answer("Resposta."));

        GroundedAIResponse result = service.process("Durante quanto tempo tenho de conservar os documentos de IRS?",
                null, List.of(qa("CIVA", 0.8918, QA_CIVA),
                        new RetrievedCase("Documento", "?", "Texto do documento.", 0.90, SourceKind.DOCUMENT, null)));

        assertThat(result.sources()).extracting(AnswerSource::title).containsExactly("Documento");
    }

    @Test
    void technicalFailure_isFailClosed_respostaLimiteWithoutProvider() {
        when(qaRepository.findScopeRowsByIdIn(any())).thenThrow(new DataAccessResourceFailureException("down"));

        GroundedAIResponse result = service.process("Pergunta sobre pensões?", null,
                List.of(qa("5795", 0.95, QA_5795)));

        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
        assertThat(result.sources()).isEmpty();
        verifyNoInteractions(aiService);
    }

    @Test
    void excludedCandidate_neverReachesPromptProviderOrSources() {
        when(qaRepository.findScopeRowsByIdIn(any())).thenReturn(corpus());
        when(exclusionRepository.findMarkerRowsByKnowledgeQaIdIn(any()))
                .thenReturn(List.of(new KnowledgeQaExclusionMarkerRow(QA_5930, "INQUILINO")));

        GroundedAIResponse result = service.process("Como inquilino, posso deduzir no IRS as obras que paguei?",
                null, List.of(qa("5930", 0.8872, QA_5930)));

        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
        assertThat(result.providerCalled()).isFalse();
        assertThat(result.sources()).isEmpty();
        verifyNoInteractions(aiService);
    }

    @Test
    void exclusionOfOneCandidate_keepsTheOthers_andThePromptOnlyHasThem() {
        when(qaRepository.findScopeRowsByIdIn(any())).thenReturn(corpus());
        when(exclusionRepository.findMarkerRowsByKnowledgeQaIdIn(any()))
                .thenReturn(List.of(new KnowledgeQaExclusionMarkerRow(QA_2721, "CALCULO")));
        when(aiService.complete(any())).thenReturn(answer("Resposta."));

        GroundedAIResponse result = service.process(
                "Queremos que o adicional do IMI seja calculado em conjunto; até quando optamos?",
                null, List.of(qa("2721", 0.90, QA_2721)));

        // "calculado em conjunto" não é cálculo do imposto: a exclusão CALCULO não dispara
        assertThat(result.sources()).extracting(AnswerSource::sourceQaId).containsExactly(QA_2721);
        ArgumentCaptor<AIRequest> request = ArgumentCaptor.forClass(AIRequest.class);
        verify(aiService).complete(request.capture());
        assertThat(request.getValue().systemPrompt()).contains("[FONTE: 2721]");
    }

    @Test
    void everythingBelowThreshold_neverQueriesScopes() {
        GroundedAIResponse result = service.process("Recebo duas pensões com retenção a 0%. Posso pedir retenção mensal?",
                null, List.of(qa("5795", 0.8695, QA_5795)));

        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
        verifyNoInteractions(qaRepository, exclusionRepository, aiService);
    }

    private static List<KnowledgeQaScopeRow> corpus() {
        return List.of(
                new KnowledgeQaScopeRow(QA_2721, KnowledgeTopic.PROCEDIMENTO_TRIBUTARIO, "AIMI — tributação conjunta",
                        "Qual é o prazo e o meio para os sujeitos passivos casados ou unidos de facto optarem pela "
                                + "tributação conjunta em AIMI?", null),
                new KnowledgeQaScopeRow(QA_5795, KnowledgeTopic.IRS, "Retenção na fonte — categoria H",
                        "Um pensionista pode pedir retenção mensal quando cada pensão, isoladamente, corresponde a "
                                + "taxa de 0%?", null),
                new KnowledgeQaScopeRow(QA_5930, KnowledgeTopic.IRS, "Categoria F — gastos dedutíveis",
                        "Que despesas podem ser deduzidas aos rendimentos prediais obtidos com o arrendamento?", null),
                new KnowledgeQaScopeRow(QA_CIVA, KnowledgeTopic.IVA, null,
                        "Durante quanto tempo deve um sujeito passivo de IVA conservar os registos e respectivos "
                                + "documentos de suporte?", null));
    }

    private static RetrievedCase qa(String title, double similarity, UUID qaId) {
        return new RetrievedCase(title, title + "?", "Resposta validada sobre " + title + ".", similarity,
                SourceKind.KNOWLEDGE_QA, qaId);
    }

    private static AIResponse answer(String content) {
        return new AIResponse("stub", "stub", content, 0, 0, 0L);
    }
}
