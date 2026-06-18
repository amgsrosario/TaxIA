package com.knowledgeflow.ai;

import com.knowledgeflow.ai.exception.AIProviderException;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Application-layer implementation of AIService.
 * Delegates to the configured AIProvider via AIProviderResolver.
 * Normalises provider exceptions into BusinessException for callers.
 */
@Service
public class DefaultAIService implements AIService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAIService.class);

    private final AIProviderResolver resolver;

    public DefaultAIService(AIProviderResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public AIResponse complete(AIRequest request) {
        AIProvider provider = resolver.resolve();
        try {
            AIResponse response = provider.generate(request);
            log.info("AI request completed — provider: {}, model: {}, taskType: {}, tokens in/out: {}/{}, duration: {}ms",
                    response.provider(), response.modelUsed(), request.taskType(),
                    response.inputTokens(), response.outputTokens(), response.durationMillis());
            return response;
        } catch (AIProviderException e) {
            log.warn("AI provider error — provider: {}, type: {}, message: {}",
                    provider.providerId(), e.getClass().getSimpleName(), e.getMessage());
            throw new BusinessException(ApiErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Erro ao contactar o serviço de IA: " + e.getMessage());
        }
    }
}
