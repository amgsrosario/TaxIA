package com.knowledgeflow.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Stub implementation used in tests and local development when no real AI
 * provider is configured. Returns a deterministic answer so tests remain fast
 * and hermetic.
 *
 * A real provider implementation (e.g. AnthropicAIService) can be added
 * in a separate config profile — Spring will prefer it over this stub via
 * {@link ConditionalOnMissingBean}.
 */
@Service
@ConditionalOnMissingBean(name = "realAIService")
public class StubAIService implements AIService {

    public static final String STUB_MODEL = "stub-v1";
    public static final String STUB_ANSWER_PREFIX = "[STUB] Resposta gerada automaticamente para: ";

    @Override
    public AIResponse complete(AIRequest request) {
        String answer = STUB_ANSWER_PREFIX + request.userMessage();
        return new AIResponse(answer, STUB_MODEL, 0, 0);
    }
}
