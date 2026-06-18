package com.knowledgeflow.ai.provider.anthropic;

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
import com.knowledgeflow.ai.exception.AIUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component("anthropic")
@ConditionalOnProperty(prefix = "knowledgeflow.ai.providers.anthropic", name = "enabled", havingValue = "true")
public class AnthropicProvider implements AIProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);

    static final String PROVIDER_ID = "anthropic";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String DEFAULT_SYSTEM_PROMPT = """
            És um assistente especializado em direito fiscal português e europeu.
            Responde sempre em português de Portugal, de forma clara, precisa e profissional.
            Quando não tiveres certeza, indica-o explicitamente.
            """;

    private final RestClient restClient;
    private final String model;
    private final int maxTokens;

    public AnthropicProvider(AIProperties properties) {
        AIProperties.ProviderConfig config = properties.providers() != null
                ? properties.providers().get(PROVIDER_ID)
                : null;
        if (config == null) {
            throw new AIConfigurationException("Anthropic provider configuration not found under knowledgeflow.ai.providers.anthropic");
        }
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new AIConfigurationException("ANTHROPIC_API_KEY is required but not set");
        }
        if (config.model() == null || config.model().isBlank()) {
            throw new AIConfigurationException("knowledgeflow.ai.providers.anthropic.model is required but not set");
        }
        this.model = config.model();
        this.maxTokens = config.maxTokens() != null ? config.maxTokens() : 1024;
        this.restClient = RestClient.builder()
                .baseUrl(API_URL)
                .defaultHeader("x-api-key", config.apiKey())
                .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                .defaultHeader("content-type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("AnthropicProvider initialized — model: {}", this.model);
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public AIResponse generate(AIRequest request) {
        String system = request.systemPrompt() != null ? request.systemPrompt() : DEFAULT_SYSTEM_PROMPT;
        var body = new MessagesRequest(
                model,
                maxTokens,
                system,
                List.of(new Message("user", request.userMessage()))
        );

        long start = System.currentTimeMillis();
        try {
            MessagesResponse response = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> status.value() == 401,
                            (req, res) -> {
                                throw new AIAuthenticationException("Anthropic authentication failed — check ANTHROPIC_API_KEY");
                            })
                    .onStatus(status -> status.value() == 429,
                            (req, res) -> {
                                throw new AIRateLimitException("Anthropic rate limit exceeded");
                            })
                    .onStatus(status -> status.value() == 503 || status.value() == 504,
                            (req, res) -> {
                                throw new AIUnavailableException("Anthropic service temporarily unavailable: " + res.getStatusCode());
                            })
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, res) -> {
                                log.error("Anthropic API error — status: {}", res.getStatusCode());
                                throw new AIProviderException("Anthropic API error: " + res.getStatusCode());
                            })
                    .body(MessagesResponse.class);

            if (response == null || response.content() == null || response.content().isEmpty()) {
                throw new AIInvalidResponseException("Empty or null response from Anthropic API");
            }

            String content = response.content().get(0).text();
            int inputTokens = response.usage() != null ? response.usage().inputTokens() : 0;
            int outputTokens = response.usage() != null ? response.usage().outputTokens() : 0;
            long duration = System.currentTimeMillis() - start;

            log.debug("Anthropic response — model: {}, tokens in/out: {}/{}, duration: {}ms",
                    model, inputTokens, outputTokens, duration);

            return new AIResponse(PROVIDER_ID, model, content, inputTokens, outputTokens, duration);

        } catch (AIProviderException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error calling Anthropic API", e);
            throw new AIProviderException("Unexpected error calling Anthropic API", e);
        }
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    record MessagesRequest(
            String model,
            @JsonProperty("max_tokens") int maxTokens,
            String system,
            List<Message> messages
    ) {}

    record Message(String role, String content) {}

    record MessagesResponse(
            String id,
            List<ContentBlock> content,
            Usage usage
    ) {}

    record ContentBlock(String type, String text) {}

    record Usage(
            @JsonProperty("input_tokens") int inputTokens,
            @JsonProperty("output_tokens") int outputTokens
    ) {}
}
