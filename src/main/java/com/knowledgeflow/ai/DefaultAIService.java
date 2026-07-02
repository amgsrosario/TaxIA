package com.knowledgeflow.ai;

import com.knowledgeflow.ai.exception.AIConfigurationException;
import com.knowledgeflow.ai.exception.AIInvalidResponseException;
import com.knowledgeflow.ai.exception.AIProviderException;
import com.knowledgeflow.ai.exception.AIRateLimitException;
import com.knowledgeflow.ai.exception.AITimeoutException;
import com.knowledgeflow.ai.exception.AIUnavailableException;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.common.observability.KnowledgeFlowMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Application-layer implementation of AIService.
 * Delegates to the configured AIProvider via AIProviderResolver.
 *
 * Error handling:
 * - AIConfigurationException: configuration problem — propagates as-is (→ HTTP 500 via GlobalExceptionHandler)
 * - AIProviderException subtypes: remote provider errors — converted to BusinessException with a
 *   DISTINCT error code per failure class (timeout, rate limit, unavailable, invalid response),
 *   stable client-safe message and cause preserved.
 */
@Service
public class DefaultAIService implements AIService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAIService.class);

    private final AIProviderResolver resolver;
    private final KnowledgeFlowMetrics metrics;

    public DefaultAIService(AIProviderResolver resolver, KnowledgeFlowMetrics metrics) {
        this.resolver = resolver;
        this.metrics = metrics;
    }

    @Override
    public AIResponse complete(AIRequest request) {
        AIProvider provider = resolver.resolve();
        long start = System.currentTimeMillis();
        try {
            AIResponse response = provider.generate(request);
            metrics.recordAiRequest(response.provider(), "success", response.durationMillis());
            log.info("AI request completed — provider: {}, model: {}, taskType: {}, tokens in/out: {}/{}, duration: {}ms",
                    response.provider(), response.modelUsed(), request.taskType(),
                    response.inputTokens(), response.outputTokens(), response.durationMillis());
            return response;
        } catch (AIConfigurationException e) {
            metrics.recordAiProviderError(provider.providerId(), "configuration");
            log.error("AI configuration error — provider: {}, taskType: {}: {}",
                    provider.providerId(), request.taskType(), e.getMessage());
            throw e;
        } catch (AIProviderException e) {
            String errorType = errorTypeOf(e);
            metrics.recordAiProviderError(provider.providerId(), errorType);
            metrics.recordAiRequest(provider.providerId(), errorType,
                    System.currentTimeMillis() - start);
            log.warn("AI provider error — provider: {}, taskType: {}, type: {}, errorCode: {}, duration: {}ms, message: {}",
                    provider.providerId(), request.taskType(), e.getClass().getSimpleName(),
                    codeFor(e), System.currentTimeMillis() - start, e.getMessage());
            throw new BusinessException(codeFor(e),
                    "Erro ao contactar o serviço de inteligência artificial.", e);
        }
    }

    /** Distinct, client-safe error code per provider failure class. */
    private static ApiErrorCode codeFor(AIProviderException e) {
        if (e instanceof AITimeoutException)         return ApiErrorCode.AI_PROVIDER_TIMEOUT;
        if (e instanceof AIRateLimitException)       return ApiErrorCode.AI_RATE_LIMIT_ERROR;
        if (e instanceof AIUnavailableException)     return ApiErrorCode.AI_PROVIDER_UNAVAILABLE;
        if (e instanceof AIInvalidResponseException) return ApiErrorCode.AI_PROVIDER_INVALID_RESPONSE;
        return ApiErrorCode.EXTERNAL_SERVICE_ERROR;
    }

    private static String errorTypeOf(AIProviderException e) {
        if (e instanceof AITimeoutException)         return "timeout";
        if (e instanceof AIRateLimitException)       return "rate_limited";
        if (e instanceof AIUnavailableException)     return "unavailable";
        if (e instanceof AIInvalidResponseException) return "invalid_response";
        return "provider_error";
    }
}
