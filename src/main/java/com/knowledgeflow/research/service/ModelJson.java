package com.knowledgeflow.research.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

/** Leitura tolerante do JSON devolvido pelo modelo: o primeiro objecto {...} do texto. */
final class ModelJson {

    private ModelJson() {
    }

    static Optional<JsonNode> firstObject(ObjectMapper mapper, String text) {
        if (text == null) return Optional.empty();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return Optional.empty();
        try {
            return Optional.of(mapper.readTree(text.substring(start, end + 1)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText().strip();
    }
}
