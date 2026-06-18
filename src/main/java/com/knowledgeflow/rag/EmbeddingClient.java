package com.knowledgeflow.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP implementation of EmbeddingService — talks to the sentence-transformers microservice.
 * Disabled in the "test" profile; StubEmbeddingService is used instead.
 */
@Component
@Profile("!test")
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingClient implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final String baseUrl;
    private final RestTemplate restTemplate;

    public EmbeddingClient(EmbeddingProperties props) {
        this.baseUrl = props.baseUrl();
        this.restTemplate = new RestTemplate();
    }

    public List<Float> embedQuery(String text) {
        return embed(List.of(text), true).get(0);
    }

    public List<Float> embedPassage(String text) {
        return embed(List.of(text), false).get(0);
    }

    private List<List<Float>> embed(List<String> texts, boolean isQuery) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<EmbedRequest> request = new HttpEntity<>(new EmbedRequest(texts, isQuery), headers);
            EmbedResponse response = restTemplate.postForObject(baseUrl + "/embed", request, EmbedResponse.class);
            if (response == null || response.embeddings() == null) {
                throw new BusinessException(ApiErrorCode.EXTERNAL_SERVICE_ERROR, "Resposta vazia do serviço de embeddings");
            }
            return response.embeddings();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Embedding service error — baseUrl={}, error={}", baseUrl, e.toString(), e);
            throw new BusinessException(ApiErrorCode.EXTERNAL_SERVICE_ERROR, "Erro ao contactar o serviço de embeddings");
        }
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
