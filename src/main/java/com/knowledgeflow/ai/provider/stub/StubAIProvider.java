package com.knowledgeflow.ai.provider.stub;

import com.knowledgeflow.ai.AIProvider;
import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stub provider for tests and local development without a real API key.
 * Returns a deterministic, hermetic response — no external calls.
 */
@Component("stub")
@ConditionalOnProperty(prefix = "knowledgeflow.ai.providers.stub", name = "enabled", havingValue = "true")
public class StubAIProvider implements AIProvider {

    public static final String PROVIDER_ID = "stub";
    public static final String STUB_MODEL = "stub-v1";
    public static final String STUB_ANSWER_PREFIX = "[STUB] Resposta gerada automaticamente para: ";

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public AIResponse generate(AIRequest request) {
        String answer = STUB_ANSWER_PREFIX + request.userMessage();
        return new AIResponse(PROVIDER_ID, STUB_MODEL, answer, 0, 0, 0L);
    }
}
