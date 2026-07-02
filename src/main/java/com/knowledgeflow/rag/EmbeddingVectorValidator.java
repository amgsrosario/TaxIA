package com.knowledgeflow.rag;

import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import java.util.List;

/**
 * Rejects vectors that would corrupt the pgvector index: wrong dimension,
 * NaN, infinity, null values or emptiness. Deterministic — callers must never
 * retry an EMBEDDING_INVALID_VECTOR failure.
 */
public final class EmbeddingVectorValidator {

    private EmbeddingVectorValidator() {
    }

    public static void validate(List<Float> vector, int expectedDimension) {
        if (vector == null || vector.isEmpty()) {
            throw new BusinessException(ApiErrorCode.EMBEDDING_INVALID_VECTOR,
                    "O serviço de embeddings devolveu um vector vazio");
        }
        if (vector.size() != expectedDimension) {
            throw new BusinessException(ApiErrorCode.EMBEDDING_INVALID_VECTOR,
                    "Dimensão do vector inválida: esperado %d, recebido %d"
                            .formatted(expectedDimension, vector.size()));
        }
        for (Float value : vector) {
            if (value == null || value.isNaN() || value.isInfinite()) {
                throw new BusinessException(ApiErrorCode.EMBEDDING_INVALID_VECTOR,
                        "O vector de embedding contém valores inválidos (null, NaN ou infinito)");
            }
        }
    }
}
