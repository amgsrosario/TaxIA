package com.knowledgeflow.ai.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.AIService;
import com.knowledgeflow.ai.grounding.scope.FiscalScopeFilter;
import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import com.knowledgeflow.rag.RagSearchService.SourceKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * M4B (ADR-007): os fundamentos expostos pelo grounding são exactamente os candidatos usados como
 * contexto — acima do threshold (0.88), com conteúdo, sem a deduplicação por título das fontes — e
 * nenhum quando o provider não é chamado ou a resposta é rejeitada.
 */
@ExtendWith(MockitoExtension.class)
class M4BFoundationsGroundingTest {

    private static final GroundingProperties PROPS = new GroundingProperties(true, 1, 1, 0.88, true, true);

    @Mock private AIService aiService;
    private GroundingService service;

    @BeforeEach
    void setUp() {
        service = new GroundingService(new ContextSufficiencyEvaluator(PROPS), new AnswerGroundingValidator(PROPS),
                new SafeResponseFactory(), aiService, PROPS, FiscalScopeFilter.disabled());
    }

    @Test
    @DisplayName("Candidato abaixo do threshold (HIGH hipotético) não é fundamento; os usados são todos, sem dedup")
    void belowThreshold_isNotAFoundation_andSameTitleKeepsBoth() {
        when(aiService.complete(any())).thenReturn(new AIResponse("stub", "stub", "Resposta.", 0, 0, 0L));
        UUID used1 = UUID.randomUUID();
        UUID used2 = UUID.randomUUID();
        UUID tail = UUID.randomUUID();

        GroundedAIResponse result = service.process("P?", null, List.of(
                qa("Mesmo título", 0.95, used1), qa("Mesmo título", 0.90, used2), qa("Cauda", 0.85, tail)));

        assertThat(result.sources()).extracting(AnswerSource::sourceQaId).containsExactly(used1);
        assertThat(result.foundations()).extracting(GroundingFoundation::sourceQaId).containsExactly(used1, used2);
    }

    @Test
    @DisplayName("Revisão #6: um caso enviado ao prompt sem conteúdo (DOCUMENT) também é fundamento")
    void caseWithoutContent_sentToThePrompt_isAFoundation() {
        when(aiService.complete(any())).thenReturn(new AIResponse("stub", "stub", "Resposta.", 0, 0, 0L));
        UUID used = UUID.randomUUID();

        GroundedAIResponse result = service.process("P?", null, List.of(
                qa("Caso", 0.95, used), new RetrievedCase("Documento", "Documento?", null, 0.93)));

        assertThat(result.foundations()).extracting(GroundingFoundation::title).containsExactly("Caso", "Documento");
        assertThat(result.foundations()).extracting(GroundingFoundation::sourceQaId).containsExactly(used, null);
    }

    @Test
    @DisplayName("Resposta-limite antes do LLM: sem fundamentos")
    void refusal_hasNoFoundations() {
        GroundedAIResponse result = service.process("P?", null, List.of(qa("Cauda", 0.80, UUID.randomUUID())));

        assertThat(result.providerCalled()).isFalse();
        assertThat(result.foundations()).isEmpty();
    }

    @Test
    @DisplayName("Resposta rejeitada pelo validador: sem fundamentos (comportamento próprio da rejeição)")
    void rejected_hasNoFoundations() {
        when(aiService.complete(any())).thenReturn(new AIResponse("stub", "stub", "O prazo é de 90 dias.", 0, 0, 0L));

        GroundedAIResponse result = service.process("P?", null, List.of(qa("Caso", 0.95, UUID.randomUUID())));

        assertThat(result.responseRejected()).isTrue();
        assertThat(result.foundations()).isEmpty();
    }

    private static RetrievedCase qa(String title, double similarity, UUID qaId) {
        return new RetrievedCase(title, title + "?", "Resposta validada sobre " + title + ".", similarity,
                SourceKind.KNOWLEDGE_QA, qaId);
    }
}
