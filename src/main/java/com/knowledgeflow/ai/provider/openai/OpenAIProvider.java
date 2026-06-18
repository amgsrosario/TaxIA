package com.knowledgeflow.ai.provider.openai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.knowledgeflow.ai.AIProperties;
import com.knowledgeflow.ai.AIProvider;
import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.exception.AIAuthenticationException;
import com.knowledgeflow.ai.exception.AIConfigurationException;
import com.knowledgeflow.ai.exception.AIInvalidResponseException;
import com.knowledgeflow.ai.exception.AIProviderException;
import com.knowledgeflow.ai.exception.AIRateLimitException;
import com.knowledgeflow.ai.exception.AITimeoutException;
import com.knowledgeflow.ai.exception.AIUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;

@Component("openai")
@ConditionalOnProperty(prefix = "knowledgeflow.ai.providers.openai", name = "enabled", havingValue = "true")
public class OpenAIProvider implements AIProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAIProvider.class);

    static final String PROVIDER_ID = "openai";
    private static final String API_URL = "https://api.openai.com/v1/responses";
    private static final String DEFAULT_SYSTEM_PROMPT = """
            És um assistente especializado em direito fiscal português e europeu.
            Responde sempre em português de Portugal, de forma clara, precisa e profissional.
            Quando não tiveres certeza, indica-o explicitamente.
            """;

    private final RestClient restClient;
    private final String model;
    private final int maxOutputTokens;

    public OpenAIProvider(AIProperties properties, RestClient.Builder restClientBuilder) {
        AIProperties.ProviderConfig config = properties.providers() != null
                ? properties.providers().get(PROVIDER_ID)
                : null;
        if (config == null) {
            throw new AIConfigurationException(
                    "OpenAI provider configuration not found under knowledgeflow.ai.providers.openai");
        }
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new AIConfigurationException("OPENAI_API_KEY is required but not set");
        }
        if (config.model() == null || config.model().isBlank()) {
            throw new AIConfigurationException("OPENAI_MODEL is required but not set");
        }
        this.model = config.model();
        this.maxOutputTokens = config.maxTokens() != null ? config.maxTokens() : 1024;
        this.restClient = restClientBuilder
                .baseUrl(API_URL)
                .defaultHeader("Authorization", "Bearer " + config.apiKey())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("OpenAIProvider initialized — model: {}", this.model);
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public AIResponse generate(AIRequest request) {
        String instructions = request.systemPrompt() != null ? request.systemPrompt() : DEFAULT_SYSTEM_PROMPT;
        var body = new ResponsesRequest(model, instructions, request.userMessage(), maxOutputTokens);

        long start = System.currentTimeMillis();
        try {
            ResponsesResponse response = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> status.value() == 401 || status.value() == 403,
                            (req, res) -> {
                                String requestId = res.getHeaders().getFirst("x-request-id");
                                log.error("OpenAI authentication failed — status: {}, x-request-id: {}",
                                        res.getStatusCode(), requestId);
                                throw new AIAuthenticationException(
                                        "OpenAI authentication failed — check OPENAI_API_KEY");
                            })
                    .onStatus(status -> status.value() == 429,
                            (req, res) -> {
                                String requestId = res.getHeaders().getFirst("x-request-id");
                                log.warn("OpenAI rate limit exceeded — status: {}, x-request-id: {}",
                                        res.getStatusCode(), requestId);
                                throw new AIRateLimitException("OpenAI rate limit exceeded");
                            })
                    .onStatus(status -> status.value() == 500 || status.value() == 502
                            || status.value() == 503 || status.value() == 504,
                            (req, res) -> {
                                String requestId = res.getHeaders().getFirst("x-request-id");
                                log.error("OpenAI service error — status: {}, x-request-id: {}",
                                        res.getStatusCode(), requestId);
                                throw new AIUnavailableException(
                                        "OpenAI service unavailable: " + res.getStatusCode());
                            })
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, res) -> {
                                String requestId = res.getHeaders().getFirst("x-request-id");
                                log.error("OpenAI API error — status: {}, x-request-id: {}",
                                        res.getStatusCode(), requestId);
                                throw new AIProviderException("OpenAI API error: " + res.getStatusCode());
                            })
                    .body(ResponsesResponse.class);

            validateStatus(response);

            String content = extractText(response);
            // AIResponse.inputTokens/outputTokens use int (primitive); 0 = metric not provided by the API.
            int inputTokens = response.usage() != null ? response.usage().inputTokens() : 0;
            int outputTokens = response.usage() != null ? response.usage().outputTokens() : 0;
            long duration = System.currentTimeMillis() - start;
            String modelUsed = response.model() != null ? response.model() : model;

            log.debug("OpenAI response — model: {}, tokens in/out: {}/{}, duration: {}ms",
                    modelUsed, inputTokens, outputTokens, duration);

            return new AIResponse(PROVIDER_ID, modelUsed, content, inputTokens, outputTokens, duration);

        } catch (AIProviderException e) {
            throw e;
        } catch (ResourceAccessException e) {
            if (isTimeoutCause(e)) {
                log.error("OpenAI request timed out — {}", e.getMessage());
                throw new AITimeoutException("OpenAI request timed out", e);
            }
            log.error("OpenAI transport error — {}", e.getMessage());
            throw new AIUnavailableException("OpenAI service unreachable", e);
        } catch (Exception e) {
            log.error("Unexpected error calling OpenAI API", e);
            throw new AIProviderException("Unexpected error calling OpenAI API", e);
        }
    }

    private void validateStatus(ResponsesResponse response) {
        String status = response.status();
        if (status == null) {
            // Responses API always includes status in synchronous calls;
            // null indicates a malformed or unrecognised response format.
            log.error("OpenAI response missing status field — model: {}", response.model());
            throw new AIInvalidResponseException("OpenAI response missing status field");
        }
        switch (status) {
            case "completed" -> { /* proceed to extractText */ }
            case "incomplete" -> {
                String reason = response.incompleteDetails() != null
                        ? response.incompleteDetails().reason() : null;
                log.warn("OpenAI response incomplete — model: {}, reason: {}", response.model(), reason);
                throw new AIInvalidResponseException("OpenAI response incomplete");
            }
            case "failed" -> {
                log.error("OpenAI response failed — model: {}", response.model());
                throw new AIInvalidResponseException("OpenAI response failed");
            }
            default -> {
                log.error("OpenAI unexpected response status: {} — model: {}", status, response.model());
                throw new AIInvalidResponseException("OpenAI unexpected response status: " + status);
            }
        }
    }

    private String extractText(ResponsesResponse response) {
        if (response == null || response.output() == null) {
            throw new AIInvalidResponseException("Null or empty response from OpenAI API");
        }
        return response.output().stream()
                .filter(item -> "message".equals(item.type()) && item.content() != null)
                .flatMap(item -> item.content().stream())
                .filter(c -> "output_text".equals(c.type()) && c.text() != null && !c.text().isBlank())
                .map(ContentItem::text)
                .findFirst()
                .orElseThrow(() -> new AIInvalidResponseException(
                        "No usable text content in OpenAI response"));
    }

    // Traverse the cause chain to distinguish real timeouts from other transport failures.
    // SocketTimeoutException: read or connect timeout from HttpURLConnection / RestTemplate default factory.
    // HttpTimeoutException: timeout from Java HTTP Client (JdkClientHttpRequestFactory).
    private static boolean isTimeoutCause(Throwable t) {
        while (t != null) {
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    record ResponsesRequest(
            String model,
            String instructions,
            String input,
            @JsonProperty("max_output_tokens") Integer maxOutputTokens
    ) {}

    record ResponsesResponse(
            String id,
            String model,
            String status,
            @JsonProperty("incomplete_details") IncompleteDetails incompleteDetails,
            List<OutputItem> output,
            Usage usage
    ) {}

    record IncompleteDetails(String reason) {}

    record OutputItem(
            String type,
            List<ContentItem> content
    ) {}

    record ContentItem(
            String type,
            String text
    ) {}

    record Usage(
            @JsonProperty("input_tokens") int inputTokens,
            @JsonProperty("output_tokens") int outputTokens
    ) {}
}
