package com.knowledgeflow.common.web;

import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Propagates the current correlationId (from the MDC, set by
 * {@link CorrelationIdFilter}) to outbound HTTP calls — AI providers and the
 * embeddings service. No-op when there is no active correlation id.
 */
public final class CorrelationIdPropagation implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null && !request.getHeaders().containsKey(CorrelationIdFilter.HEADER)) {
            request.getHeaders().add(CorrelationIdFilter.HEADER, correlationId);
        }
        return execution.execute(request, body);
    }
}
