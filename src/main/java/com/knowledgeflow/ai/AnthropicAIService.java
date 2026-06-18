package com.knowledgeflow.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Anthropic Messages API integration.
 * Activated when knowledgeflow.ai.anthropic.api-key is set (non-blank).
 * The bean name "realAIService" causes Spring to skip StubAIService.
 */
@Service("realAIService")
@ConditionalOnExpression("!'${knowledgeflow.ai.anthropic.api-key:}'.isBlank()")
@EnableConfigurationProperties(AnthropicProperties.class)
public class AnthropicAIService implements AIService {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAIService.class);

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    private static final String DEFAULT_SYSTEM_PROMPT = """
            És um assistente especializado em direito fiscal português e europeu.
            Responde sempre em português de Portugal, de forma clara, precisa e profissional.
            Quando não tiveres certeza, indica-o explicitamente.
            """;

    private final RestClient restClient;
    private final AnthropicProperties props;

    public AnthropicAIService(AnthropicProperties props) {
        this.props = props;
        this.restClient = RestClient.builder()
                .baseUrl(API_URL)
                .defaultHeader("x-api-key", props.apiKey())
                .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                .defaultHeader("content-type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("AnthropicAIService initialized — model: {}", props.model());
    }

    @Override
    public AIResponse complete(AIRequest request) {
        String system = request.systemPrompt() != null ? request.systemPrompt() : DEFAULT_SYSTEM_PROMPT;

        var body = new MessagesRequest(
                props.model(),
                props.maxTokens(),
                system,
                List.of(new Message("user", request.userMessage()))
        );

        try {
            MessagesResponse response = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        String error = new String(res.getBody().readAllBytes());
                        log.error("Anthropic API error {}: {}", res.getStatusCode(), error);
                        throw new BusinessException(ApiErrorCode.EXTERNAL_SERVICE_ERROR,
                                "Erro ao contactar o serviço de IA: " + res.getStatusCode());
                    })
                    .body(MessagesResponse.class);

            if (response == null || response.content() == null || response.content().isEmpty()) {
                throw new BusinessException(ApiErrorCode.EXTERNAL_SERVICE_ERROR, "Resposta vazia do serviço de IA");
            }

            String content = response.content().get(0).text();
            int inputTokens = response.usage() != null ? response.usage().inputTokens() : 0;
            int outputTokens = response.usage() != null ? response.usage().outputTokens() : 0;

            log.debug("Anthropic response — model: {}, tokens in/out: {}/{}", props.model(), inputTokens, outputTokens);

            return new AIResponse(content, props.model(), inputTokens, outputTokens);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error calling Anthropic API", e);
            throw new BusinessException(ApiErrorCode.EXTERNAL_SERVICE_ERROR, "Erro inesperado ao contactar o serviço de IA");
        }
    }

    // ── Request DTOs ──────────────────────────────────────────────────────────

    record MessagesRequest(
            String model,
            @JsonProperty("max_tokens") int maxTokens,
            String system,
            List<Message> messages
    ) {}

    record Message(String role, String content) {}

    // ── Response DTOs ─────────────────────────────────────────────────────────

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
