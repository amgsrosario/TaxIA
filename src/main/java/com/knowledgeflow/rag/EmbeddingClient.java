package com.knowledgeflow.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.common.observability.KnowledgeFlowMetrics;
import com.knowledgeflow.common.resilience.TransientRetry;
import com.knowledgeflow.common.web.CorrelationIdPropagation;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP implementation of EmbeddingService — talks to the sentence-transformers microservice.
 * Disabled in the "test" profile; StubEmbeddingService is used instead.
 *
 * Resilience: explicit connect/read timeouts, bounded retry on transient failures
 * (timeout / connection refused), and strict vector validation (dimension, NaN,
 * infinity, emptiness) before any value reaches pgvector.
 */
@Component
@Profile("!(test | pgtest)")
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingClient implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final String baseUrl;
    private final RestTemplate restTemplate;
    private final EmbeddingProperties props;
    private final KnowledgeFlowMetrics metrics;

    public EmbeddingClient(EmbeddingProperties props, KnowledgeFlowMetrics metrics) {
        this.baseUrl = props.baseUrl();
        this.props = props;
        this.metrics = metrics;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) props.connectTimeoutOrDefault().toMillis());
        requestFactory.setReadTimeout((int) props.readTimeoutOrDefault().toMillis());
        this.restTemplate = new RestTemplate(requestFactory);
        this.restTemplate.getInterceptors().add(new CorrelationIdPropagation());
        log.info("EmbeddingClient initialized — baseUrl: {}, connectTimeout: {}ms, readTimeout: {}ms, "
                        + "expectedDimension: {}, retryMaxAttempts: {}",
                baseUrl, props.connectTimeoutOrDefault().toMillis(), props.readTimeoutOrDefault().toMillis(),
                props.expectedDimensionOrDefault(), props.retryMaxAttemptsOrDefault());
    }

    public List<Float> embedQuery(String text) {
        return embedWithRetry(List.of(text), true).get(0);
    }

    public List<Float> embedPassage(String text) {
        return embedWithRetry(List.of(text), false).get(0);
    }

    private List<List<Float>> embedWithRetry(List<String> texts, boolean isQuery) {
        // Retry policy: only EMBEDDING_TIMEOUT / EMBEDDING_UNAVAILABLE are transient.
        // EMBEDDING_INVALID_VECTOR is deterministic and never retried.
        try {
            List<List<Float>> result = TransientRetry.call("embeddings.embed",
                    props.retryMaxAttemptsOrDefault(), props.retryBackoffOrDefault(),
                    e -> e instanceof BusinessException be
                            && (be.getCode() == ApiErrorCode.EMBEDDING_TIMEOUT
                                    || be.getCode() == ApiErrorCode.EMBEDDING_UNAVAILABLE),
                    () -> embed(texts, isQuery));
            metrics.recordEmbeddingRequest("success");
            return result;
        } catch (BusinessException e) {
            metrics.recordEmbeddingRequest(switch (e.getCode()) {
                case EMBEDDING_TIMEOUT -> "timeout";
                case EMBEDDING_INVALID_VECTOR -> "invalid_vector";
                default -> "unavailable";
            });
            throw e;
        }
    }

    private List<List<Float>> embed(List<String> texts, boolean isQuery) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<EmbedRequest> request = new HttpEntity<>(new EmbedRequest(texts, isQuery), headers);
            EmbedResponse response = restTemplate.postForObject(baseUrl + "/embed", request, EmbedResponse.class);
            if (response == null || response.embeddings() == null
                    || response.embeddings().size() != texts.size()) {
                throw new BusinessException(ApiErrorCode.EMBEDDING_UNAVAILABLE,
                        "Resposta vazia ou incompleta do serviço de embeddings");
            }
            response.embeddings().forEach(v ->
                    EmbeddingVectorValidator.validate(v, props.expectedDimensionOrDefault()));
            return response.embeddings();
        } catch (BusinessException e) {
            throw e;
        } catch (ResourceAccessException e) {
            if (isTimeoutCause(e)) {
                log.error("Embedding service timed out — baseUrl={}", baseUrl);
                throw new BusinessException(ApiErrorCode.EMBEDDING_TIMEOUT,
                        "O serviço de embeddings não respondeu dentro do timeout configurado", e);
            }
            log.error("Embedding service unreachable — baseUrl={}, error={}", baseUrl, e.toString());
            throw new BusinessException(ApiErrorCode.EMBEDDING_UNAVAILABLE,
                    "O serviço de embeddings está indisponível", e);
        } catch (Exception e) {
            log.error("Embedding service error — baseUrl={}, error={}", baseUrl, e.toString(), e);
            throw new BusinessException(ApiErrorCode.EMBEDDING_UNAVAILABLE,
                    "Erro ao contactar o serviço de embeddings", e);
        }
    }

    private static boolean isTimeoutCause(Throwable t) {
        while (t != null) {
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    static class EmbedRequest {
        public final List<String> texts;
        @JsonProperty("is_query")
        public final boolean isQuery;

        EmbedRequest(List<String> texts, boolean isQuery) {
            this.texts = texts;
            this.isQuery = isQuery;
        }
    }

    record EmbedResponse(List<List<Float>> embeddings) {}
}
