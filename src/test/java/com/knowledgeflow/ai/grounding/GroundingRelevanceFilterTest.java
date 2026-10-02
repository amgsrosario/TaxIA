package com.knowledgeflow.ai.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.AIService;
import com.knowledgeflow.ai.documented.CuratedSourceResolver;
import com.knowledgeflow.ai.documented.ResolvedAnswerSource;
import com.knowledgeflow.ai.grounding.scope.FiscalScopeFilter;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRow;
import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import com.knowledgeflow.rag.RagSearchService.SourceKind;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Filtro de relevância candidato a candidato (M4) com o limiar interino calibrado em M4-CAL (0.88):
 * candidatos com similaridade não finita ou abaixo do limiar não chegam ao evaluator, ao prompt,
 * às fontes nem à resolução de fontes curadas (M3).
 */
@ExtendWith(MockitoExtension.class)
class GroundingRelevanceFilterTest {

    private static final double THRESHOLD = 0.88;
    private static final GroundingProperties PROPS =
            new GroundingProperties(true, 1, 1, THRESHOLD, true, true);

    private static final UUID QA_HIGH = UUID.fromString("00000000-0000-0000-0000-000000005930");
    private static final UUID QA_TAIL = UUID.fromString("00000000-0000-0000-0000-000000002721");

    @Mock private AIService aiService;
    @Mock private KnowledgeSourceReferenceRepository sourceRepository;

    private GroundingService service;

    @BeforeEach
    void setUp() {
        service = new GroundingService(new ContextSufficiencyEvaluator(PROPS), new AnswerGroundingValidator(PROPS),
                new SafeResponseFactory(), aiService, PROPS, FiscalScopeFilter.disabled());
    }

    // A
    @Test
    void allAboveThreshold_areAllKept() {
        when(aiService.complete(any())).thenReturn(answer("Resposta."));

        GroundedAIResponse result = service.process("P?", null,
                List.of(qa("Caso A", 0.95, null), qa("Caso B", 0.90, null)));

        assertThat(result.sources()).extracting(AnswerSource::title).containsExactly("Caso A", "Caso B");
    }

    // B + H + I
    @Test
    void mixedScores_keepOnlyCandidatesAtOrAboveThreshold_inOriginalOrder_withoutLowTail() {
        when(aiService.complete(any())).thenReturn(answer("Resposta."));

        GroundedAIResponse result = service.process("P?", null, List.of(
                qa("Relevante 1", 0.93, QA_HIGH),
                qa("Cauda 1", 0.8434, QA_TAIL),
                qa("Relevante 2", 0.89, null),
                qa("Cauda 2", 0.81, null)));

        assertThat(result.sources()).extracting(AnswerSource::title).containsExactly("Relevante 1", "Relevante 2");

        // A cauda também não entra no prompt enviado ao provider.
        ArgumentCaptor<AIRequest> request = ArgumentCaptor.forClass(AIRequest.class);
        verify(aiService).complete(request.capture());
        assertThat(request.getValue().systemPrompt())
                .contains("Relevante 1", "Relevante 2")
                .doesNotContain("Cauda 1", "Cauda 2");
    }

    // C + J
    @Test
    void allBelowThreshold_becomesInsufficientContext_respostaLimite_withoutCallingProvider() {
        GroundedAIResponse result = service.process("P?", null,
                List.of(qa("Irrelevante 1", 0.8745, QA_HIGH), qa("Irrelevante 2", 0.80, QA_TAIL)));

        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
        assertThat(result.providerCalled()).isFalse();
        assertThat(result.sources()).isEmpty();
        verifyNoInteractions(aiService);
    }

    // D
    @Test
    void scoreExactlyAtThreshold_isKept() {
        when(aiService.complete(any())).thenReturn(answer("Resposta."));

        GroundedAIResponse result = service.process("P?", null, List.of(qa("No limiar", THRESHOLD, null)));

        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.SUPPORTED);
        assertThat(result.sources()).extracting(AnswerSource::title).containsExactly("No limiar");
    }

    // E + F + G
    @Test
    void nonFiniteScores_areAlwaysRejected() {
        for (double score : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            GroundedAIResponse result = service.process("P?", null, List.of(qa("Score " + score, score, null)));

            assertThat(result.supportStatus()).as("score=%s", score).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
            assertThat(result.sources()).as("score=%s", score).isEmpty();
        }
        verify(aiService, never()).complete(any());
    }

    @Test
    void nonFiniteScore_isRejectedEvenNextToARelevantCandidate() {
        when(aiService.complete(any())).thenReturn(answer("Resposta."));

        GroundedAIResponse result = service.process("P?", null, List.of(
                qa("Infinito", Double.POSITIVE_INFINITY, null), qa("Relevante", 0.92, null), qa("NaN", Double.NaN, null)));

        assertThat(result.sources()).extracting(AnswerSource::title).containsExactly("Relevante");
    }

    @Test
    void validator_onlySeesKeptCandidates_soAClaimSupportedOnlyByTheTailIsRejected() {
        // A taxa de 23% só existe na cauda descartada: o validador não a pode usar como suporte.
        when(aiService.complete(any())).thenReturn(answer("A taxa aplicável é de 23%."));

        GroundedAIResponse result = service.process("P?", null, List.of(
                new RetrievedCase("Relevante", "Relevante?", "Resposta sem valores.", 0.92,
                        SourceKind.KNOWLEDGE_QA, null),
                new RetrievedCase("Cauda", "Cauda?", "A taxa normal é de 23%.", 0.84,
                        SourceKind.KNOWLEDGE_QA, null)));

        assertThat(result.responseRejected()).isTrue();
        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.REJECTED_UNSUPPORTED);
    }

    @Test
    void nullCandidates_becomeInsufficientContext_withoutCallingProvider() {
        GroundedAIResponse result = service.process("P?", null, null);

        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
        assertThat(result.sources()).isEmpty();
        verifyNoInteractions(aiService);
    }

    // K
    @Test
    void discardedCandidate_neverReachesCuratedSourceResolution() {
        when(aiService.complete(any())).thenReturn(answer("Resposta."));
        when(sourceRepository.findRowsByQuestionAnswerIdIn(any())).thenReturn(List.of(
                new KnowledgeSourceReferenceRow(QA_HIGH, UUID.randomUUID(), KnowledgeSourceType.LEGISLATION,
                        "Código do IRS — Artigo 41.º", "CIRS, art. 41.º", "https://x.gov.pt/irs41", OffsetDateTime.now())));

        GroundedAIResponse result = service.process("P?", null,
                List.of(qa("Rendimentos prediais", 0.92, QA_HIGH), qa("AIMI", 0.8434, QA_TAIL)));
        List<ResolvedAnswerSource> resolved = new CuratedSourceResolver(sourceRepository).resolve(result.sources());

        assertThat(result.sources()).extracting(AnswerSource::sourceQaId).containsExactly(QA_HIGH);
        verify(sourceRepository).findRowsByQuestionAnswerIdIn(Set.of(QA_HIGH));
        assertThat(resolved).extracting(r -> r.curated().title()).containsExactly("Código do IRS — Artigo 41.º");
    }

    private static RetrievedCase qa(String title, double similarity, UUID qaId) {
        return new RetrievedCase(title, title + "?", "Resposta validada sobre " + title + ".", similarity,
                SourceKind.KNOWLEDGE_QA, qaId);
    }

    private static AIResponse answer(String content) {
        return new AIResponse("stub", "stub", content, 0, 0, 0L);
    }
}
