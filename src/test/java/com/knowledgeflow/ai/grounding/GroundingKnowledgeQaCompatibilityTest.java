package com.knowledgeflow.ai.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.AIService;
import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import com.knowledgeflow.rag.RagSearchService.SourceKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies that Q&A knowledge entries retrieved from the RAG pipeline integrate
 * correctly with GroundingService — sources are surfaced, sourceKind is preserved,
 * SUPPORTED is given when the answer matches the context, and REJECTED_UNSUPPORTED
 * when the provider invents content not present in context.
 *
 * No external calls are made (no OpenAI, no Anthropic).
 */
@ExtendWith(MockitoExtension.class)
class GroundingKnowledgeQaCompatibilityTest {

    private static final GroundingProperties PROPS =
            new GroundingProperties(true, 1, 1, 0.0, true, true);

    @Mock
    private AIService aiService;

    private GroundingService service;

    @BeforeEach
    void setUp() {
        var evaluator = new ContextSufficiencyEvaluator(PROPS);
        var validator = new AnswerGroundingValidator(PROPS);
        var safeFactory = new SafeResponseFactory();
        service = new GroundingService(evaluator, validator, safeFactory, aiService, PROPS);
    }

    // -----------------------------------------------------------------------
    // TC-GQA-1: KNOWLEDGE_QA source surfaces in response sources list
    // -----------------------------------------------------------------------

    @Test
    void knowledgeQa_sourceAppearsInResponseSources() {
        UUID qaId = UUID.randomUUID();
        RetrievedCase qaCase = new RetrievedCase(
                "Periodicidade do IVA",
                "Qual a periodicidade do IVA para volume de negócios superior a 650 000€?",
                "Contribuintes com volume de negócios superior a 650 000€ ficam sujeitos ao regime mensal de IVA.",
                0.92,
                SourceKind.KNOWLEDGE_QA,
                qaId);

        when(aiService.complete(any(AIRequest.class)))
                .thenReturn(new AIResponse(
                        "stub", "stub",
                        "Contribuintes com volume de negócios superior a 650 000€ ficam sujeitos ao regime mensal de IVA.",
                        10, 20, 100));

        GroundedAIResponse response = service.process(
                "Qual a periodicidade do IVA para volume de negócios superior a 650 000€?",
                null,
                List.of(qaCase));

        assertThat(response.sources()).isNotEmpty();
        assertThat(response.sources())
                .anyMatch(s -> s.title() != null && s.title().contains("Periodicidade do IVA"));
    }

    // -----------------------------------------------------------------------
    // TC-GQA-2: sourceKind KNOWLEDGE_QA is preserved in RetrievedCase
    // -----------------------------------------------------------------------

    @Test
    void knowledgeQa_sourceKindIsKnowledgeQa() {
        UUID qaId = UUID.randomUUID();
        RetrievedCase qaCase = new RetrievedCase(
                "Taxa IVA reduzida",
                "Qual a taxa reduzida de IVA?",
                "A taxa reduzida de IVA é de 6%.",
                0.88,
                SourceKind.KNOWLEDGE_QA,
                qaId);

        assertThat(qaCase.sourceKind()).isEqualTo(SourceKind.KNOWLEDGE_QA);
        assertThat(qaCase.sourceQaId()).isEqualTo(qaId);
    }

    // -----------------------------------------------------------------------
    // TC-GQA-3: DOCUMENT sourceKind has null sourceQaId
    // -----------------------------------------------------------------------

    @Test
    void document_sourceKindHasNullSourceQaId() {
        RetrievedCase docCase = new RetrievedCase(
                "CIVA comentado",
                "Qual o prazo de entrega da DP?",
                "A declaração periódica deve ser entregue até ao dia 20 do segundo mês seguinte.",
                0.85);

        assertThat(docCase.sourceKind()).isEqualTo(SourceKind.DOCUMENT);
        assertThat(docCase.sourceQaId()).isNull();
    }

    // -----------------------------------------------------------------------
    // TC-GQA-4: SUPPORTED verdict when provider answer matches QA context
    // -----------------------------------------------------------------------

    @Test
    void knowledgeQa_supportedWhenAnswerMatchesContext() {
        UUID qaId = UUID.randomUUID();
        String contextContent = "Volume de negócios superior a 650 000€ implica periodicidade mensal de IVA.";
        RetrievedCase qaCase = new RetrievedCase(
                "Periodicidade mensal IVA",
                "Quando é obrigatória a periodicidade mensal de IVA?",
                contextContent,
                0.91,
                SourceKind.KNOWLEDGE_QA,
                qaId);

        when(aiService.complete(any(AIRequest.class)))
                .thenReturn(new AIResponse(
                        "stub", "stub",
                        "Volume de negócios superior a 650 000€ implica periodicidade mensal de IVA.",
                        10, 20, 100));

        GroundedAIResponse response = service.process(
                "Quando é obrigatória a periodicidade mensal de IVA?",
                null,
                List.of(qaCase));

        assertThat(response.supportStatus()).isNotEqualTo(AnswerSupportStatus.REJECTED_UNSUPPORTED);
        assertThat(response.responseRejected()).isFalse();
    }

    // -----------------------------------------------------------------------
    // TC-GQA-5: REJECTED_UNSUPPORTED when provider invents a tax rate not in context
    // -----------------------------------------------------------------------

    @Test
    void knowledgeQa_rejectedWhenProviderInventsTaxRate() {
        UUID qaId = UUID.randomUUID();
        // Context contains only procedural information — no tax rate
        RetrievedCase qaCase = new RetrievedCase(
                "Periodicidade do IVA",
                "Quando é obrigatória a periodicidade mensal de IVA?",
                "Contribuintes com volume de negócios elevado ficam sujeitos ao regime mensal.",
                0.89,
                SourceKind.KNOWLEDGE_QA,
                qaId);

        // Provider invents a tax rate not present anywhere in the context
        when(aiService.complete(any(AIRequest.class)))
                .thenReturn(new AIResponse(
                        "stub", "stub",
                        "A taxa de IVA aplicável a esta operação é de 23%.",
                        10, 20, 100));

        GroundedAIResponse response = service.process(
                "Quando é obrigatória a periodicidade mensal de IVA?",
                null,
                List.of(qaCase));

        // The response must be rejected because "23%" is not in the context
        assertThat(response.responseRejected())
                .as("Provider-invented tax rate should cause response rejection")
                .isTrue();
        assertThat(response.supportStatus()).isEqualTo(AnswerSupportStatus.REJECTED_UNSUPPORTED);
    }

    // -----------------------------------------------------------------------
    // TC-GQA-6: Mixed DOCUMENT + KNOWLEDGE_QA sources both contribute to context
    // -----------------------------------------------------------------------

    @Test
    void mixedSources_bothDocumentAndQaContributeToContext() {
        UUID qaId = UUID.randomUUID();
        RetrievedCase docCase = new RetrievedCase(
                "CIVA Art. 41",
                "Qual o prazo de entrega da declaração periódica?",
                "A declaração periódica deve ser entregue até ao dia 20 do segundo mês seguinte ao período de imposto.",
                0.87);
        RetrievedCase qaCase = new RetrievedCase(
                "Periodicidade mensal",
                "Quando é obrigatória a periodicidade mensal?",
                "Quando o volume de negócios do ano anterior excede 650 000€.",
                0.83,
                SourceKind.KNOWLEDGE_QA,
                qaId);

        when(aiService.complete(any(AIRequest.class)))
                .thenReturn(new AIResponse(
                        "stub", "stub",
                        "A declaração periódica deve ser entregue até ao dia 20 do segundo mês seguinte. "
                                + "Quando o volume de negócios do ano anterior excede 650 000€ é obrigatória a periodicidade mensal.",
                        15, 30, 150));

        GroundedAIResponse response = service.process(
                "Qual o prazo e a periodicidade da declaração periódica de IVA?",
                null,
                List.of(docCase, qaCase));

        assertThat(response.providerCalled()).isTrue();
        assertThat(response.sources()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(response.responseRejected()).isFalse();
    }

    // -----------------------------------------------------------------------
    // TC-GQA-7: Empty context — provider not called, refusal returned
    // -----------------------------------------------------------------------

    @Test
    void emptyContext_providerNotCalledAndRefusalReturned() {
        GroundedAIResponse response = service.process(
                "Qual a taxa de IRC em Marte?",
                null,
                List.of());

        assertThat(response.providerCalled()).isFalse();
        assertThat(response.supportStatus()).isEqualTo(AnswerSupportStatus.INSUFFICIENT_CONTEXT);
    }

    // -----------------------------------------------------------------------
    // TC-GQA-8: sourceQaId round-trips correctly through RetrievedCase record
    // -----------------------------------------------------------------------

    @Test
    void knowledgeQa_sourceQaIdRoundTrip() {
        UUID expected = UUID.fromString("00000000-0000-0000-0000-000000000042");
        RetrievedCase qaCase = new RetrievedCase(
                "title", "question", "content", 0.9,
                SourceKind.KNOWLEDGE_QA, expected);

        assertThat(qaCase.sourceQaId()).isEqualTo(expected);
        assertThat(qaCase.sourceKind()).isEqualTo(SourceKind.KNOWLEDGE_QA);
    }
}
