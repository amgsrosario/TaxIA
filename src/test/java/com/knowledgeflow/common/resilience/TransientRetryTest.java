package com.knowledgeflow.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TransientRetryTest {

    private static final class TransientFailure extends RuntimeException {
        TransientFailure(String m) { super(m); }
    }

    private static final class PermanentFailure extends RuntimeException {
        PermanentFailure(String m) { super(m); }
    }

    @Test
    void returnsImmediatelyOnSuccess() {
        AtomicInteger calls = new AtomicInteger();
        String result = TransientRetry.call("op", 3, Duration.ZERO,
                e -> e instanceof TransientFailure,
                () -> { calls.incrementAndGet(); return "ok"; });

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void retriesTransientFailureUntilSuccess() {
        AtomicInteger calls = new AtomicInteger();
        String result = TransientRetry.call("op", 3, Duration.ZERO,
                e -> e instanceof TransientFailure,
                () -> {
                    if (calls.incrementAndGet() < 3) throw new TransientFailure("falha transitoria");
                    return "recuperado";
                });

        assertThat(result).isEqualTo("recuperado");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void neverExceedsMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> TransientRetry.call("op", 3, Duration.ZERO,
                e -> e instanceof TransientFailure,
                () -> { calls.incrementAndGet(); throw new TransientFailure("sempre"); }))
                .isInstanceOf(TransientFailure.class);

        assertThat(calls.get()).isEqualTo(3); // limitado — nunca infinito
    }

    @Test
    void doesNotRetryNonTransientFailure() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> TransientRetry.call("op", 3, Duration.ZERO,
                e -> e instanceof TransientFailure,
                () -> { calls.incrementAndGet(); throw new PermanentFailure("400"); }))
                .isInstanceOf(PermanentFailure.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void maxAttemptsBelowOneIsNormalisedToSingleAttempt() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> TransientRetry.call("op", 0, Duration.ZERO,
                e -> true,
                () -> { calls.incrementAndGet(); throw new TransientFailure("x"); }))
                .isInstanceOf(TransientFailure.class);

        assertThat(calls.get()).isEqualTo(1);
    }
}
