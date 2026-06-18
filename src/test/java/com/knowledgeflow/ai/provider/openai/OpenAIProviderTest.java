package com.knowledgeflow.ai.provider.openai;

import com.knowledgeflow.ai.AIProperties;
import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.exception.AIAuthenticationException;
import com.knowledgeflow.ai.exception.AIConfigurationException;
import com.knowledgeflow.ai.exception.AIInvalidResponseException;
import com.knowledgeflow.ai.exception.AIProviderException;
import com.knowledgeflow.ai.exception.AIRateLimitException;
import com.knowledgeflow.ai.exception.AITimeoutException;
import com.knowledgeflow.ai.exception.AIUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAIProviderTest {

    private static final String TEST_API_KEY = "sk-test-openai-key";
    private static final String TEST_MODEL = "gpt-4o";
    private static final String API_URL = "https://api.openai.com/v1/responses";

    private MockRestServiceServer server;
    private OpenAIProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new OpenAIProvider(validProps(), builder);
    }

    private AIProperties validProps() {
        return new AIProperties("openai", Map.of(
                "openai", new AIProperties.ProviderConfig(true, TEST_API_KEY, TEST_MODEL, 1024)
        ));
    }

    /** Returns a well-formed completed response with the given text. */
    private String responseJson(String text) {
        return """
                {
                  "id": "resp_test",
                  "model": "%s",
                  "status": "completed",
                  "output": [
                    {
                      "type": "message",
                      "content": [
                        { "type": "output_text", "text": "%s" }
                      ]
                    }
                  ],
                  "usage": { "input_tokens": 50, "output_tokens": 100 }
                }
                """.formatted(TEST_MODEL, text);
    }

    // ── Validação do construtor ───────────────────────────────────────────────

    @Test
    void constructorThrowsWhenApiKeyIsBlank() {
        RestClient.Builder b = RestClient.builder();
        AIProperties props = new AIProperties("openai", Map.of(
                "openai", new AIProperties.ProviderConfig(true, "", TEST_MODEL, 1024)
        ));
        assertThatThrownBy(() -> new OpenAIProvider(props, b))
                .isInstanceOf(AIConfigurationException.class)
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void constructorThrowsWhenApiKeyIsNull() {
        RestClient.Builder b = RestClient.builder();
        AIProperties props = new AIProperties("openai", Map.of(
                "openai", new AIProperties.ProviderConfig(true, null, TEST_MODEL, 1024)
        ));
        assertThatThrownBy(() -> new OpenAIProvider(props, b))
                .isInstanceOf(AIConfigurationException.class)
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void constructorThrowsWhenModelIsBlank() {
        RestClient.Builder b = RestClient.builder();
        AIProperties props = new AIProperties("openai", Map.of(
                "openai", new AIProperties.ProviderConfig(true, TEST_API_KEY, "", 1024)
        ));
        assertThatThrownBy(() -> new OpenAIProvider(props, b))
                .isInstanceOf(AIConfigurationException.class)
                .hasMessageContaining("OPENAI_MODEL");
    }

    @Test
    void constructorThrowsWhenModelIsNull() {
        RestClient.Builder b = RestClient.builder();
        AIProperties props = new AIProperties("openai", Map.of(
                "openai", new AIProperties.ProviderConfig(true, TEST_API_KEY, null, 1024)
        ));
        assertThatThrownBy(() -> new OpenAIProvider(props, b))
                .isInstanceOf(AIConfigurationException.class)
                .hasMessageContaining("OPENAI_MODEL");
    }

    @Test
    void constructorThrowsWhenConfigSectionMissing() {
        RestClient.Builder b = RestClient.builder();
        assertThatThrownBy(() -> new OpenAIProvider(new AIProperties("openai", Map.of()), b))
                .isInstanceOf(AIConfigurationException.class);
    }

    @Test
    void constructorThrowsWhenProvidersMapIsNull() {
        RestClient.Builder b = RestClient.builder();
        assertThatThrownBy(() -> new OpenAIProvider(new AIProperties("openai", null), b))
                .isInstanceOf(AIConfigurationException.class);
    }

    @Test
    void providerIdIsOpenai() {
        assertThat(provider.providerId()).isEqualTo("openai");
    }

    // ── Validação do pedido HTTP ──────────────────────────────────────────────

    @Test
    void sendsRequestToCorrectEndpointWithAuthHeader() {
        server.expect(requestTo(API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TEST_API_KEY))
                .andExpect(header("Content-Type", "application/json"))
                .andRespond(withSuccess(responseJson("ok"), MediaType.APPLICATION_JSON));

        provider.generate(new AIRequest("System", "User question"));

        server.verify();
    }

    @Test
    void sendsModelConfiguredInProperties() {
        server.expect(requestTo(API_URL))
                .andExpect(content().string(containsString("\"model\":\"" + TEST_MODEL + "\"")))
                .andRespond(withSuccess(responseJson("ok"), MediaType.APPLICATION_JSON));

        provider.generate(new AIRequest("System", "Question"));

        server.verify();
    }

    @Test
    void sendsInstructionsSeparateFromInput() {
        server.expect(requestTo(API_URL))
                .andExpect(content().string(containsString("\"instructions\":\"System instruction\"")))
                .andExpect(content().string(containsString("\"input\":\"User question\"")))
                .andRespond(withSuccess(responseJson("ok"), MediaType.APPLICATION_JSON));

        provider.generate(new AIRequest("System instruction", "User question"));

        server.verify();
    }

    @Test
    void sendsMaxOutputTokens() {
        server.expect(requestTo(API_URL))
                .andExpect(content().string(containsString("\"max_output_tokens\":1024")))
                .andRespond(withSuccess(responseJson("ok"), MediaType.APPLICATION_JSON));

        provider.generate(new AIRequest("System", "Question"));

        server.verify();
    }

    // ── Mapeamento da resposta ────────────────────────────────────────────────

    @Test
    void mapsContentCorrectly() {
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(responseJson("Resposta fiscal detalhada"), MediaType.APPLICATION_JSON));

        AIResponse result = provider.generate(new AIRequest("Question"));

        assertThat(result.provider()).isEqualTo("openai");
        assertThat(result.content()).isEqualTo("Resposta fiscal detalhada");
    }

    @Test
    void preservesModelFromApiResponse() {
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(responseJson("ok"), MediaType.APPLICATION_JSON));

        AIResponse result = provider.generate(new AIRequest("Question"));

        assertThat(result.modelUsed()).isEqualTo(TEST_MODEL);
    }

    @Test
    void mapsTokensFromUsageBlock() {
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(responseJson("ok"), MediaType.APPLICATION_JSON));

        AIResponse result = provider.generate(new AIRequest("Question"));

        assertThat(result.inputTokens()).isEqualTo(50);
        assertThat(result.outputTokens()).isEqualTo(100);
    }

    @Test
    void returnsZeroAsUnavailableIndicatorWhenUsageBlockAbsent() {
        // AIResponse uses int (primitive); the contract documents 0 as "metric not provided by the API".
        // Zero does NOT mean the model processed zero tokens.
        String json = """
                {
                  "id": "resp_no_usage",
                  "model": "gpt-4o",
                  "status": "completed",
                  "output": [
                    {
                      "type": "message",
                      "content": [{ "type": "output_text", "text": "ok" }]
                    }
                  ]
                }
                """;
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        AIResponse result = provider.generate(new AIRequest("Question"));

        assertThat(result.inputTokens()).isEqualTo(0);
        assertThat(result.outputTokens()).isEqualTo(0);
    }

    @Test
    void preservesExplicitZeroTokensFromApi() {
        // If the API explicitly reports 0 tokens, that value must be preserved.
        String json = """
                {
                  "id": "resp_zero",
                  "model": "gpt-4o",
                  "status": "completed",
                  "output": [
                    {
                      "type": "message",
                      "content": [{ "type": "output_text", "text": "ok" }]
                    }
                  ],
                  "usage": { "input_tokens": 0, "output_tokens": 0 }
                }
                """;
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        AIResponse result = provider.generate(new AIRequest("Question"));

        assertThat(result.inputTokens()).isEqualTo(0);
        assertThat(result.outputTokens()).isEqualTo(0);
    }

    @Test
    void extractsTextSkippingNonMessageOutputItems() {
        String json = """
                {
                  "id": "resp_multi",
                  "model": "gpt-4o",
                  "status": "completed",
                  "output": [
                    { "type": "reasoning", "content": [] },
                    {
                      "type": "message",
                      "content": [{ "type": "output_text", "text": "Texto da resposta" }]
                    }
                  ],
                  "usage": { "input_tokens": 10, "output_tokens": 5 }
                }
                """;
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        AIResponse result = provider.generate(new AIRequest("Question"));

        assertThat(result.content()).isEqualTo("Texto da resposta");
    }

    @Test
    void populatesDurationMillis() {
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(responseJson("ok"), MediaType.APPLICATION_JSON));

        AIResponse result = provider.generate(new AIRequest("Question"));

        assertThat(result.durationMillis()).isGreaterThanOrEqualTo(0);
    }

    // ── Estados da Responses API ──────────────────────────────────────────────

    @Test
    void statusCompletedWithTextProducesResponse() {
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(responseJson("Resposta válida"), MediaType.APPLICATION_JSON));

        AIResponse result = provider.generate(new AIRequest("Question"));

        assertThat(result.content()).isEqualTo("Resposta válida");
    }

    @Test
    void statusCompletedWithoutTextThrowsInvalidResponse() {
        String json = """
                {
                  "id": "resp_no_text",
                  "model": "gpt-4o",
                  "status": "completed",
                  "output": [
                    {
                      "type": "message",
                      "content": [{ "type": "image", "text": null }]
                    }
                  ],
                  "usage": { "input_tokens": 5, "output_tokens": 0 }
                }
                """;
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.generate(new AIRequest("Question")))
                .isInstanceOf(AIInvalidResponseException.class);
    }

    @Test
    void statusIncompleteThrowsInvalidResponse() {
        String json = """
                {
                  "id": "resp_incomplete",
                  "model": "gpt-4o",
                  "status": "incomplete",
                  "output": [
                    {
                      "type": "message",
                      "content": [{ "type": "output_text", "text": "Resposta parcial..." }]
                    }
                  ],
                  "usage": { "input_tokens": 20, "output_tokens": 50 }
                }
                """;
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        // Partial content must never be returned as a valid response.
        assertThatThrownBy(() -> provider.generate(new AIRequest("Question")))
                .isInstanceOf(AIInvalidResponseException.class);
    }

    @Test
    void statusIncompleteWithMaxTokensReasonDoesNotReturnPartialContent() {
        String json = """
                {
                  "id": "resp_trunc",
                  "model": "gpt-4o",
                  "status": "incomplete",
                  "incomplete_details": { "reason": "max_output_tokens" },
                  "output": [
                    {
                      "type": "message",
                      "content": [{ "type": "output_text", "text": "Texto cortado a meio" }]
                    }
                  ],
                  "usage": { "input_tokens": 20, "output_tokens": 1024 }
                }
                """;
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.generate(new AIRequest("Question")))
                .isInstanceOf(AIInvalidResponseException.class);
    }

    @Test
    void statusFailedThrowsInvalidResponse() {
        String json = """
                {
                  "id": "resp_failed",
                  "model": "gpt-4o",
                  "status": "failed",
                  "output": [],
                  "usage": { "input_tokens": 10, "output_tokens": 0 }
                }
                """;
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.generate(new AIRequest("Question")))
                .isInstanceOf(AIInvalidResponseException.class);
    }

    @Test
    void statusUnexpectedThrowsInvalidResponse() {
        String json = """
                {
                  "id": "resp_queued",
                  "model": "gpt-4o",
                  "status": "queued",
                  "output": [],
                  "usage": null
                }
                """;
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.generate(new AIRequest("Question")))
                .isInstanceOf(AIInvalidResponseException.class);
    }

    @Test
    void statusAbsentThrowsInvalidResponse() {
        // Responses API always includes status in synchronous calls.
        // A missing status field is treated as a malformed response.
        String json = """
                {
                  "id": "resp_no_status",
                  "model": "gpt-4o",
                  "output": [
                    {
                      "type": "message",
                      "content": [{ "type": "output_text", "text": "ok" }]
                    }
                  ],
                  "usage": { "input_tokens": 10, "output_tokens": 5 }
                }
                """;
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.generate(new AIRequest("Question")))
                .isInstanceOf(AIInvalidResponseException.class);
    }

    // ── Mapeamento de erros HTTP ──────────────────────────────────────────────

    @Test
    void maps401ToAuthenticationException() {
        server.expect(requestTo(API_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> provider.generate(new AIRequest("Question")))
                .isInstanceOf(AIAuthenticationException.class);
    }

    @Test
    void maps403ToAuthenticationException() {
        server.expect(requestTo(API_URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> provider.generate(new AIRequest("Question")))
                .isInstanceOf(AIAuthenticationException.class);
    }

    @Test
    void maps429ToRateLimitException() {
        server.expect(requestTo(API_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> provider.generate(new AIRequest("Question")))
                .isInstanceOf(AIRateLimitException.class);
    }

    @Test
    void maps503ToUnavailableException() {
        server.expect(requestTo(API_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> provider.generate(new AIRequest("Question")))
                .isInstanceOf(AIUnavailableException.class);
    }

    @Test
    void maps500ToUnavailableException() {
        server.expect(requestTo(API_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> provider.generate(new AIRequest("Question")))
                .isInstanceOf(AIUnavailableException.class);
    }

    @Test
    void maps400ToGenericProviderException() {
        server.expect(requestTo(API_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> provider.generate(new AIRequest("Question")))
                .isInstanceOf(AIProviderException.class)
                .isNotInstanceOf(AIAuthenticationException.class)
                .isNotInstanceOf(AIRateLimitException.class)
                .isNotInstanceOf(AIUnavailableException.class);
    }

    // ── Classificação de falhas de transporte ─────────────────────────────────

    @Test
    void mapsSocketTimeoutToTimeoutException() {
        server.expect(requestTo(API_URL))
                .andRespond(request -> { throw new SocketTimeoutException("Read timed out"); });

        assertThatThrownBy(() -> provider.generate(new AIRequest("Question")))
                .isInstanceOf(AITimeoutException.class);
    }

    @Test
    void mapsConnectExceptionToUnavailableException() {
        server.expect(requestTo(API_URL))
                .andRespond(request -> { throw new ConnectException("Connection refused"); });

        assertThatThrownBy(() -> provider.generate(new AIRequest("Question")))
                .isInstanceOf(AIUnavailableException.class)
                .isNotInstanceOf(AITimeoutException.class);
    }

    @Test
    void mapsUnknownHostToUnavailableException() {
        server.expect(requestTo(API_URL))
                .andRespond(request -> { throw new UnknownHostException("api.openai.com"); });

        assertThatThrownBy(() -> provider.generate(new AIRequest("Question")))
                .isInstanceOf(AIUnavailableException.class)
                .isNotInstanceOf(AITimeoutException.class);
    }

    @Test
    void mapsTlsErrorToUnavailableException() {
        server.expect(requestTo(API_URL))
                .andRespond(request -> { throw new SSLException("TLS handshake failed"); });

        assertThatThrownBy(() -> provider.generate(new AIRequest("Question")))
                .isInstanceOf(AIUnavailableException.class)
                .isNotInstanceOf(AITimeoutException.class);
    }

    @Test
    void mapsGenericTransportErrorToUnavailableException() {
        server.expect(requestTo(API_URL))
                .andRespond(request -> { throw new java.io.IOException("Network error"); });

        assertThatThrownBy(() -> provider.generate(new AIRequest("Question")))
                .isInstanceOf(AIUnavailableException.class)
                .isNotInstanceOf(AITimeoutException.class);
    }

    @Test
    void preservesOriginalCauseOnTimeout() {
        SocketTimeoutException root = new SocketTimeoutException("Read timed out");
        server.expect(requestTo(API_URL))
                .andRespond(request -> { throw root; });

        assertThatThrownBy(() -> provider.generate(new AIRequest("Question")))
                .isInstanceOf(AITimeoutException.class)
                .hasCauseInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class);
    }

    @Test
    void preservesOriginalCauseOnTransportError() {
        ConnectException root = new ConnectException("Connection refused");
        server.expect(requestTo(API_URL))
                .andRespond(request -> { throw root; });

        assertThatThrownBy(() -> provider.generate(new AIRequest("Question")))
                .isInstanceOf(AIUnavailableException.class)
                .hasCauseInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(ConnectException.class);
    }
}
