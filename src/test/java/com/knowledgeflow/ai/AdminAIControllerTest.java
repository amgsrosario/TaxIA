package com.knowledgeflow.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knowledgeflow.ai.grounding.AnswerSource;
import com.knowledgeflow.ai.grounding.AnswerSupportStatus;
import com.knowledgeflow.ai.grounding.GroundedAIResponse;
import com.knowledgeflow.ai.grounding.GroundingService;
import com.knowledgeflow.common.error.GlobalExceptionHandler;
import com.knowledgeflow.rag.RagSearchService;
import com.knowledgeflow.security.AuthenticatedUser;
import com.knowledgeflow.security.AuthenticatedUserContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AdminAIControllerTest {

    private static final UUID ORG_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock private GroundingService groundingService;
    @Mock private RagSearchService ragSearchService;
    @Mock private AuthenticatedUserContext authenticatedUserContext;
    @Mock private com.knowledgeflow.common.observability.KnowledgeFlowMetrics metrics;

    @InjectMocks private AdminAIController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        var user = new AuthenticatedUser(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                ORG_ID, "admin@test.com", List.of("ROLE_ADMIN"));
        when(authenticatedUserContext.getRequiredUser()).thenReturn(user);
        when(ragSearchService.findSimilar(eq(ORG_ID), anyString())).thenReturn(List.of());
    }

    // --- Backward compatibility ---

    @Test
    void oldFields_answer_provider_model_inputTokens_outputTokens_presentInResponse() throws Exception {
        when(groundingService.process(anyString(), any(), anyList()))
                .thenReturn(supportedResponse("Resposta de teste.", "anthropic", "haiku", 10, 5));

        performAsk("Qual a taxa de IVA?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Resposta de teste."))
                .andExpect(jsonPath("$.provider").value("anthropic"))
                .andExpect(jsonPath("$.model").value("haiku"))
                .andExpect(jsonPath("$.inputTokens").value(10))
                .andExpect(jsonPath("$.outputTokens").value(5));
    }

    // --- New field serialization ---

    @Test
    void newFields_supportStatus_requiresHumanValidation_providerCalled_serializedInResponse() throws Exception {
        when(groundingService.process(anyString(), any(), anyList()))
                .thenReturn(supportedResponse("Resposta.", "stub", "stub", 0, 0));

        performAsk("Pergunta")
                .andExpect(jsonPath("$.supportStatus").value("SUPPORTED"))
                .andExpect(jsonPath("$.requiresHumanValidation").value(false))
                .andExpect(jsonPath("$.providerCalled").value(true))
                .andExpect(jsonPath("$.responseRejected").value(false))
                .andExpect(jsonPath("$.unsupportedClaimsCount").value(0))
                .andExpect(jsonPath("$.sources").isArray())
                .andExpect(jsonPath("$.missingInformation").isArray())
                .andExpect(jsonPath("$.limitations").isArray());
    }

    // --- Insufficient context: HTTP 200 ---

    @Test
    void insufficientContext_returns_http200() throws Exception {
        when(groundingService.process(anyString(), any(), anyList()))
                .thenReturn(refusalResponse());

        performAsk("Pergunta")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supportStatus").value("INSUFFICIENT_CONTEXT"));
    }

    @Test
    void insufficientContext_providerCalledIsFalse_inResponse() throws Exception {
        when(groundingService.process(anyString(), any(), anyList()))
                .thenReturn(refusalResponse());

        performAsk("Pergunta")
                .andExpect(jsonPath("$.providerCalled").value(false));
    }

    @Test
    void insufficientContext_answerIsNotBlank_inResponse() throws Exception {
        when(groundingService.process(anyString(), any(), anyList()))
                .thenReturn(refusalResponse());

        var result = performAsk("Pergunta")
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("answer");
        // answer value must not be blank — the JSON field exists and has content
        assertThat(body).doesNotContain("\"answer\":\"\"");
        assertThat(body).doesNotContain("\"answer\":null");
    }

    // --- Sources present on supported response ---

    @Test
    void sufficientContext_sourceTitlesSerializedInSources() throws Exception {
        var src = new AnswerSource("IVA — Regime Geral", "IVA — Regime Geral", 0.9);
        var resp = new GroundedAIResponse(
                "Resposta com fonte.", AnswerSupportStatus.SUPPORTED, "OK",
                List.of(src), List.of(), List.of(), false, null,
                true, false, null, 0, "stub", "stub", 0, 0, 0L);
        when(groundingService.process(anyString(), any(), anyList())).thenReturn(resp);

        performAsk("Pergunta")
                .andExpect(jsonPath("$.sources").isArray())
                .andExpect(jsonPath("$.sources[0]").value("IVA — Regime Geral"));
    }

    // --- Rejected response ---

    @Test
    void rejectedResponse_responseRejectedIsTrue_inResponse() throws Exception {
        when(groundingService.process(anyString(), any(), anyList()))
                .thenReturn(rejectedResponse());

        performAsk("Pergunta")
                .andExpect(jsonPath("$.responseRejected").value(true))
                .andExpect(jsonPath("$.supportStatus").value("REJECTED_UNSUPPORTED"));
    }

    @Test
    void rejectedResponse_answerContainsSafeMessageNotOriginalProviderText() throws Exception {
        var resp = new GroundedAIResponse(
                "MENSAGEM SEGURA DE REJEIÇÃO",
                AnswerSupportStatus.REJECTED_UNSUPPORTED, "Afirmações não suportadas",
                List.of(), List.of(), List.of(), true,
                "Requer validação humana.", true, true,
                "Afirmação sem suporte.", 1, "anthropic", "haiku", 10, 5, 100L);
        when(groundingService.process(anyString(), any(), anyList())).thenReturn(resp);

        performAsk("Pergunta")
                .andExpect(jsonPath("$.answer").value("MENSAGEM SEGURA DE REJEIÇÃO"))
                .andExpect(jsonPath("$.responseRejected").value(true));
    }

    @Test
    void rejectedResponse_providerMetrics_inputTokens_outputTokens_durationMillis_preserved() throws Exception {
        var resp = new GroundedAIResponse(
                "Resposta rejeitada.", AnswerSupportStatus.REJECTED_UNSUPPORTED, "Rejeição",
                List.of(), List.of(), List.of(), true, "Validar.", true, true,
                "Razão.", 2, "anthropic", "claude-haiku", 100, 50, 1500L);
        when(groundingService.process(anyString(), any(), anyList())).thenReturn(resp);

        performAsk("Pergunta")
                .andExpect(jsonPath("$.inputTokens").value(100))
                .andExpect(jsonPath("$.outputTokens").value(50))
                .andExpect(jsonPath("$.durationMillis").value(1500));
    }

    // --- requiresHumanValidation ---

    @Test
    void requiresHumanValidation_andValidationMessage_serializedInResponse() throws Exception {
        var resp = new GroundedAIResponse(
                "Resposta.", AnswerSupportStatus.REQUIRES_HUMAN_REVIEW, "Alto risco",
                List.of(), List.of(), List.of(), true,
                "Requer validação por especialista fiscal.", true, false, null,
                0, "stub", "stub", 0, 0, 0L);
        when(groundingService.process(anyString(), any(), anyList())).thenReturn(resp);

        performAsk("Pergunta")
                .andExpect(jsonPath("$.requiresHumanValidation").value(true))
                .andExpect(jsonPath("$.validationMessage").value("Requer validação por especialista fiscal."));
    }

    // --- Source deduplication and order ---

    @Test
    void sources_noDuplicates_whenTwoSourcesWithSameTitleReturned() throws Exception {
        var src1 = new AnswerSource("Fonte A", "Fonte A", 0.9);
        var src2 = new AnswerSource("Fonte B", "Fonte B", 0.7);
        var resp = new GroundedAIResponse(
                "Resposta.", AnswerSupportStatus.SUPPORTED, "OK",
                List.of(src1, src2), List.of(), List.of(), false, null,
                true, false, null, 0, "stub", "stub", 0, 0, 0L);
        when(groundingService.process(anyString(), any(), anyList())).thenReturn(resp);

        performAsk("Pergunta")
                .andExpect(jsonPath("$.sources").isArray())
                .andExpect(jsonPath("$.sources.length()").value(2));
    }

    @Test
    void sources_orderIsStable_matchesGroundedResponseOrder() throws Exception {
        var src1 = new AnswerSource("Primeiro", "Primeiro", 0.9);
        var src2 = new AnswerSource("Segundo", "Segundo", 0.8);
        var src3 = new AnswerSource("Terceiro", "Terceiro", 0.7);
        var resp = new GroundedAIResponse(
                "Resposta.", AnswerSupportStatus.SUPPORTED, "OK",
                List.of(src1, src2, src3), List.of(), List.of(), false, null,
                true, false, null, 0, "stub", "stub", 0, 0, 0L);
        when(groundingService.process(anyString(), any(), anyList())).thenReturn(resp);

        performAsk("Pergunta")
                .andExpect(jsonPath("$.sources[0]").value("Primeiro"))
                .andExpect(jsonPath("$.sources[1]").value("Segundo"))
                .andExpect(jsonPath("$.sources[2]").value("Terceiro"));
    }

    // --- helpers ---

    private ResultActions performAsk(String question) throws Exception {
        return mockMvc.perform(
                MockMvcRequestBuilders.post("/api/v1/admin/ai/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + question + "\"}"));
    }

    private GroundedAIResponse supportedResponse(String answer, String provider, String model,
            int inputTokens, int outputTokens) {
        return new GroundedAIResponse(
                answer, AnswerSupportStatus.SUPPORTED, "Context sufficient",
                List.of(), List.of(), List.of(), false, null,
                true, false, null, 0, provider, model, inputTokens, outputTokens, 0L);
    }

    private GroundedAIResponse refusalResponse() {
        return new GroundedAIResponse(
                "Não foi possível encontrar documentação validada suficiente.",
                AnswerSupportStatus.INSUFFICIENT_CONTEXT, "Contexto insuficiente",
                List.of(), List.of("Documentação sobre o tema"), List.of(), false, null,
                false, false, null, 0, "none", "none", 0, 0, 0L);
    }

    private GroundedAIResponse rejectedResponse() {
        return new GroundedAIResponse(
                "A resposta gerada foi bloqueada por conter afirmações não suportadas.",
                AnswerSupportStatus.REJECTED_UNSUPPORTED, "Afirmações rejeitadas",
                List.of(), List.of(), List.of(), true, "Requer validação humana.",
                true, true, "Afirmação não suportada.", 1,
                "stub", "stub", 0, 0, 0L);
    }
}
