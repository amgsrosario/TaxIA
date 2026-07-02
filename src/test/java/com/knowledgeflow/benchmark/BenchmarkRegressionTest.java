package com.knowledgeflow.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.AIService;
import com.knowledgeflow.ai.grounding.AnswerSupportStatus;
import com.knowledgeflow.ai.grounding.ContextSufficiencyEvaluator;
import com.knowledgeflow.ai.grounding.GroundingProperties;
import com.knowledgeflow.ai.grounding.GroundingService;
import com.knowledgeflow.ai.grounding.AnswerGroundingValidator;
import com.knowledgeflow.ai.grounding.SafeResponseFactory;
import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Regression tests mirroring benchmark cases TAXIA-004, 007, 008, 009, 010.
 * Zero real provider calls — AIService is always mocked.
 */
@ExtendWith(MockitoExtension.class)
class BenchmarkRegressionTest {

    private static final GroundingProperties PROPS =
            new GroundingProperties(true, 1, 1, 0.0, true, true);

    @Mock
    private AIService aiService;

    private GroundingService service;

    @BeforeEach
    void setUp() {
        var evaluator = new ContextSufficiencyEvaluator(PROPS);
        var validator = new AnswerGroundingValidator(PROPS);
        var factory = new SafeResponseFactory();
        service = new GroundingService(evaluator, validator, factory, aiService, PROPS);
    }

    /**
     * TAXIA-004: Periodicidade IVA sem contexto RAG.
     * Garante que não são inventados limiares ou valores.
     */
    @Test
    void taxia004_insufficientContext_safeRefusal_noInventedValues() {
        var result = service.process(
                "A empresa tem de declarar IVA mensalmente ou trimestralmente?",
                "Responde de forma clara. Se a informação for insuficiente, indica-o.",
                List.of());

        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
        assertThat(result.providerCalled()).isFalse();
        assertThat(result.answer()).isNotBlank();
        assertThat(result.answer()).doesNotContain("650 000");
        assertThat(result.answer()).doesNotContain("500 000");
        verify(aiService, never()).complete(any(AIRequest.class));
    }

    /**
     * TAXIA-007: Notificação da AT + imóvel → requiresHumanValidation.
     * Com contexto suficiente: REQUIRES_HUMAN_REVIEW.
     */
    @Test
    void taxia007_highRiskKeywords_withContext_requiresHumanValidation() {
        var context = List.of(new RetrievedCase(
                "Procedimento Notificação AT",
                "O que fazer perante notificação da AT?",
                "Perante notificação da Autoridade Tributária, o contribuinte deve responder no prazo indicado.",
                0.85));
        when(aiService.complete(any())).thenReturn(stub("Esta situação requer validação por especialista."));

        var result = service.process(
                "A empresa recebeu uma notificação da Autoridade Tributária contestando a dedução de IVA"
                        + " relativa a uma aquisição de imóvel. Qual é o procedimento a seguir?",
                null,
                context);

        assertThat(result.requiresHumanValidation()).isTrue();
        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.REQUIRES_HUMAN_REVIEW);
    }

    /**
     * TAXIA-007: Sem contexto RAG mas questão de risco elevado.
     * Após a correcção do ContextSufficiencyEvaluator, humanReviewRequired é propagado
     * mesmo no caminho INSUFFICIENT_CONTEXT → requiresHumanValidation=true na recusa segura.
     */
    @Test
    void taxia007_highRiskKeywords_withoutContext_requiresHumanValidation() {
        var result = service.process(
                "A empresa recebeu uma notificação da Autoridade Tributária contestando a dedução de IVA"
                        + " relativa a uma aquisição de imóvel. Qual é o procedimento a seguir?",
                null,
                List.of()); // sem contexto RAG

        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
        assertThat(result.providerCalled()).isFalse();
        assertThat(result.requiresHumanValidation()).isTrue();
        assertThat(result.validationMessage()).isNotBlank();
        verify(aiService, never()).complete(any(AIRequest.class));
    }

    /**
     * TAXIA-008: Referência legal ausente do contexto → resposta rejeitada.
     */
    @Test
    void taxia008_inventedLegalReference_triggersRejection() {
        var context = List.of(new RetrievedCase(
                "Vendas à Distância UE",
                "Empresa portuguesa vende a consumidores UE?",
                "As vendas à distância intracomunitárias estão sujeitas a regras específicas de IVA no Estado-Membro de destino.",
                0.82));
        // Stub inventa "artigo 14.º do RITI" ausente do contexto
        when(aiService.complete(any())).thenReturn(stub(
                "Nos termos do artigo 14.º do RITI, as vendas à distância ficam sujeitas a IVA no Estado-Membro do destinatário."));

        var result = service.process(
                "Uma empresa de comércio electrónico portuguesa vende produtos a consumidores finais noutros EM da UE.",
                null, context);

        assertThat(result.responseRejected()).isTrue();
        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.REJECTED_UNSUPPORTED);
        assertThat(result.providerCalled()).isTrue();
        assertThat(result.unsupportedClaimsCount()).isGreaterThan(0);
    }

    /**
     * TAXIA-009: Artigo inventado em resposta sobre dedutibilidade → rejeição.
     */
    @Test
    void taxia009_inventedArticleOnDeductibility_triggersRejection() {
        var context = List.of(new RetrievedCase(
                "Dedução IVA Representação",
                "IVA em despesas de representação?",
                "O direito à dedução de IVA em despesas de representação está sujeito a limitações legais.",
                0.80));
        // Stub inventa "artigo 21.º do CIVA" e percentagem não presente no contexto
        when(aiService.complete(any())).thenReturn(stub(
                "Nos termos do artigo 21.º do CIVA, as despesas de representação apenas permitem dedução de 50% do IVA suportado."));

        var result = service.process(
                "Uma empresa deduz IVA de despesas com refeições de colaboradores classificadas como despesas de representação.",
                null, context);

        assertThat(result.responseRejected()).isTrue();
        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.REJECTED_UNSUPPORTED);
        assertThat(result.unsupportedClaimsCount()).isGreaterThan(0);
    }

    /**
     * TAXIA-010: Taxa histórica IRC 2019 sem fonte validada → recusa segura, provider não chamado.
     */
    @Test
    void taxia010_noValidatedSource_safeRefusal_providerNotCalled() {
        var result = service.process(
                "Qual foi exactamente a taxa de IRC aplicável a pequenas e médias empresas em Portugal"
                        + " no terceiro trimestre de 2019?",
                "Nunca inventes factos, datas ou valores.",
                List.of());

        assertThat(result.supportStatus()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
        assertThat(result.providerCalled()).isFalse();
        assertThat(result.answer()).doesNotContain("21%");
        assertThat(result.answer()).doesNotContain("17%");
        assertThat(result.answer()).doesNotContain("2019");
        verify(aiService, never()).complete(any(AIRequest.class));
    }

    private AIResponse stub(String content) {
        return new AIResponse("stub", "stub", content, 10, 5, 100L);
    }
}
