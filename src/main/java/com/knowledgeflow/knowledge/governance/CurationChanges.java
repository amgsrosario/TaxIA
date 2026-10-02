package com.knowledgeflow.knowledge.governance;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Campos alterados (por ordem estável) e a natureza de cada alteração. */
public record CurationChanges(Map<String, CurationChangeKind> fields) {

    public CurationChanges {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    /** Alguma alteração material ou expansiva (nova versão se publicada; revisão se VALIDATED). */
    public boolean requiresRevalidation() {
        return fields.values().stream().anyMatch(CurationChangeKind::requiresRevalidation);
    }

    public List<String> fieldNames() {
        return List.copyOf(fields.keySet());
    }

    public List<String> revalidationFields() {
        return fields.entrySet().stream().filter(e -> e.getValue().requiresRevalidation())
                .map(Map.Entry::getKey).toList();
    }
}
