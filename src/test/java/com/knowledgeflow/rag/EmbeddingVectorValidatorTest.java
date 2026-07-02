package com.knowledgeflow.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the pgvector index against invalid embeddings:
 * dimension mismatch, NaN, infinity, null values and emptiness.
 */
class EmbeddingVectorValidatorTest {

    private static final int DIM = 768;

    private static List<Float> validVector() {
        List<Float> v = new ArrayList<>(Collections.nCopies(DIM, 0.0f));
        v.set(0, 1.0f);
        return v;
    }

    @Test
    void acceptsValid768DimensionVector() {
        assertThatCode(() -> EmbeddingVectorValidator.validate(validVector(), DIM))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNullVector() {
        assertInvalid(() -> EmbeddingVectorValidator.validate(null, DIM));
    }

    @Test
    void rejectsEmptyVector() {
        assertInvalid(() -> EmbeddingVectorValidator.validate(List.of(), DIM));
    }

    @Test
    void rejectsWrongDimension() {
        assertInvalid(() -> EmbeddingVectorValidator.validate(List.of(1.0f, 2.0f), DIM));
    }

    @Test
    void rejectsDimensionOffByOne() {
        List<Float> v = new ArrayList<>(Collections.nCopies(DIM + 1, 0.1f));
        assertInvalid(() -> EmbeddingVectorValidator.validate(v, DIM));
    }

    @Test
    void rejectsNaN() {
        List<Float> v = validVector();
        v.set(100, Float.NaN);
        assertInvalid(() -> EmbeddingVectorValidator.validate(v, DIM));
    }

    @Test
    void rejectsPositiveInfinity() {
        List<Float> v = validVector();
        v.set(200, Float.POSITIVE_INFINITY);
        assertInvalid(() -> EmbeddingVectorValidator.validate(v, DIM));
    }

    @Test
    void rejectsNegativeInfinity() {
        List<Float> v = validVector();
        v.set(300, Float.NEGATIVE_INFINITY);
        assertInvalid(() -> EmbeddingVectorValidator.validate(v, DIM));
    }

    @Test
    void rejectsNullElement() {
        List<Float> v = validVector();
        v.set(400, null);
        assertInvalid(() -> EmbeddingVectorValidator.validate(v, DIM));
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ApiErrorCode.EMBEDDING_INVALID_VECTOR));
    }
}
