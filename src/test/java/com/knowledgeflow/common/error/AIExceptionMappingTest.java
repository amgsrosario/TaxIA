package com.knowledgeflow.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.knowledgeflow.ai.exception.AIConfigurationException;
import com.knowledgeflow.ai.exception.AIProviderException;
import com.knowledgeflow.ai.exception.AIRateLimitException;
import com.knowledgeflow.ai.exception.AIUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class AIExceptionMappingTest {

    private MockMvc mockMvc;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger handlerLogger;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        handlerLogger.addAppender(logAppender);
        handlerLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logAppender);
    }

    @Test
    void aiProviderExceptionMapsTo502() throws Exception {
        mockMvc.perform(get("/ai-errors/provider"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_ERROR"))
                .andExpect(jsonPath("$.message").value("AI provider request failed"));
    }

    @Test
    void aiRateLimitExceptionMapsTo429() throws Exception {
        mockMvc.perform(get("/ai-errors/rate-limit"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AI_RATE_LIMIT_ERROR"));
    }

    @Test
    void aiUnavailableExceptionMapsTo503() throws Exception {
        mockMvc.perform(get("/ai-errors/unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_UNAVAILABLE"));
    }

    @Test
    void aiTimeoutExceptionMapsTo504() throws Exception {
        mockMvc.perform(get("/ai-errors/timeout"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_TIMEOUT"));
    }

    @Test
    void aiInvalidResponseExceptionMapsTo502() throws Exception {
        mockMvc.perform(get("/ai-errors/invalid-response"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_INVALID_RESPONSE"));
    }

    @Test
    void embeddingErrorCodesMapToDistinctStatuses() throws Exception {
        mockMvc.perform(get("/ai-errors/embedding-timeout"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("EMBEDDING_TIMEOUT"));
        mockMvc.perform(get("/ai-errors/embedding-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EMBEDDING_UNAVAILABLE"));
        mockMvc.perform(get("/ai-errors/embedding-invalid-vector"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EMBEDDING_INVALID_VECTOR"));
    }

    @Test
    void aiConfigurationExceptionMapsTo503() throws Exception {
        mockMvc.perform(get("/ai-errors/configuration"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_CONFIGURATION_ERROR"));
    }

    @Test
    void genericExceptionMapsTo500() throws Exception {
        mockMvc.perform(get("/ai-errors/generic"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void genericExceptionLogsStackTrace() throws Exception {
        mockMvc.perform(get("/ai-errors/generic"));

        boolean hasErrorLog = logAppender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getThrowableProxy() != null
                        && e.getMessage().contains("Unhandled exception"));
        assertThat(hasErrorLog).isTrue();
    }

    @Test
    void noStackTraceInClientResponse() throws Exception {
        MvcResult result = mockMvc.perform(get("/ai-errors/generic")).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("at com.")
                .doesNotContain("stackTrace")
                .doesNotContain("java.lang.RuntimeException");
    }

    @Test
    void aiProviderErrorResponseContainsNoSensitiveData() throws Exception {
        MvcResult result = mockMvc.perform(get("/ai-errors/configuration")).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("apiKey")
                .doesNotContain("Authorization")
                .doesNotContain("password")
                .doesNotContain("Bearer");
    }

    @Test
    void aiProviderExceptionLogsExceptionDetails() throws Exception {
        mockMvc.perform(get("/ai-errors/provider"));

        boolean logged = logAppender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("AI provider error")
                        && e.getThrowableProxy() != null);
        assertThat(logged).isTrue();
    }

    @Test
    void embeddingServiceErrorDistinctFromAIProviderError() throws Exception {
        // BusinessException(EXTERNAL_SERVICE_ERROR) from EmbeddingClient maps to 502 via BusinessException handler
        mockMvc.perform(get("/ai-errors/embedding-service"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_SERVICE_ERROR"));

        // AIProviderException also maps to 502 but with a different error code
        mockMvc.perform(get("/ai-errors/provider"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_ERROR"));
    }

    @RestController
    @RequestMapping("/ai-errors")
    public static class TestController {

        @GetMapping("/provider")
        public ResponseEntity<Void> provider() {
            throw new AIProviderException("downstream error");
        }

        @GetMapping("/rate-limit")
        public ResponseEntity<Void> rateLimit() {
            throw new AIRateLimitException("rate limit exceeded");
        }

        @GetMapping("/unavailable")
        public ResponseEntity<Void> unavailable() {
            throw new AIUnavailableException("service down");
        }

        @GetMapping("/timeout")
        public ResponseEntity<Void> timeout() {
            throw new com.knowledgeflow.ai.exception.AITimeoutException("provider timed out");
        }

        @GetMapping("/invalid-response")
        public ResponseEntity<Void> invalidResponse() {
            throw new com.knowledgeflow.ai.exception.AIInvalidResponseException("malformed body");
        }

        @GetMapping("/embedding-timeout")
        public ResponseEntity<Void> embeddingTimeout() {
            throw new BusinessException(ApiErrorCode.EMBEDDING_TIMEOUT, "embedding timeout");
        }

        @GetMapping("/embedding-unavailable")
        public ResponseEntity<Void> embeddingUnavailable() {
            throw new BusinessException(ApiErrorCode.EMBEDDING_UNAVAILABLE, "embedding down");
        }

        @GetMapping("/embedding-invalid-vector")
        public ResponseEntity<Void> embeddingInvalidVector() {
            throw new BusinessException(ApiErrorCode.EMBEDDING_INVALID_VECTOR, "bad vector");
        }

        @GetMapping("/configuration")
        public ResponseEntity<Void> configuration() {
            throw new AIConfigurationException("API key not configured");
        }

        @GetMapping("/generic")
        public ResponseEntity<Void> generic() {
            throw new RuntimeException("unexpected error");
        }

        @GetMapping("/embedding-service")
        public ResponseEntity<Void> embeddingService() {
            throw new BusinessException(ApiErrorCode.EXTERNAL_SERVICE_ERROR, "Erro ao contactar o serviço de embeddings");
        }
    }
}
