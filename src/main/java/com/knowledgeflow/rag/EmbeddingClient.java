package com.knowledgeflow.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Talks to the internal embeddings microservice (sentence-transformers running
 * locally — see embeddings-service/). Knowledge-base text never leaves the
 * docker network for embedding purposes.
 */
@Component
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingClient {

    private final RestClient restClient;

    public EmbeddingClient(EmbeddingProperties props) {
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .build();
    }

    public List<Float> embedQuery(String text) {
        return embed(List.of(text), true).get(0);
    }

    public List<Float> embedPassage(String text) {
        return embed(List.of(text), false).get(0);
    }

    private List<List<Float>> embed(List<String> texts, boolean isQuery) {
        try {
            EmbedResponse response = restClient.post()
                    .uri("/embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new EmbedRequest(texts, isQuery))
                    .retrieve()
                    .body(EmbedResponse.class);
            if (response == null || response.embeddings() == null) {
                throw new BusinessException(ApiErrorCode.EXTERNAL_SERVICE_ERROR, "Resposta vazia do serviço de embeddings");
            }
            return response.embeddings();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ApiErrorCode.EXTERNAL_SERVICE_ERROR, "Erro ao contactar o serviço de embeddings");
        }
    }

    record EmbedRequest(List<String> texts, @JsonProperty("is_query") boolean isQuery) {}

    record EmbedResponse(List<List<Float>> embeddings) {}
}
