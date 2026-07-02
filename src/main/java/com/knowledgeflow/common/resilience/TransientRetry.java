package com.knowledgeflow.common.resilience;

import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal bounded retry for transient failures (timeouts, 429, 5xx, transport).
 *
 * Policy: at most {@code maxAttempts} total attempts (never infinite), linear
 * backoff ({@code backoff × attempt}), retry only when {@code transientCheck}
 * accepts the exception. Non-transient failures (4xx, validation, malformed
 * responses, grounding rejections) are rethrown immediately.
 */
public final class TransientRetry {

    private static final Logger log = LoggerFactory.getLogger(TransientRetry.class);

    private TransientRetry() {
    }

    public static <T> T call(
            String operationName,
            int maxAttempts,
            Duration backoff,
            Predicate<RuntimeException> transientCheck,
            Supplier<T> action) {

        int attempts = Math.max(1, maxAttempts);
        RuntimeException last = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                last = e;
                boolean retryable = transientCheck.test(e) && attempt < attempts;
                if (!retryable) {
                    throw e;
                }
                long sleepMillis = backoff != null ? backoff.toMillis() * attempt : 0L;
                log.warn("Transient failure on {} (attempt {}/{}): {} — retrying in {}ms",
                        operationName, attempt, attempts, e.getMessage(), sleepMillis);
                if (sleepMillis > 0) {
                    try {
                        Thread.sleep(sleepMillis);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last;
    }
}
