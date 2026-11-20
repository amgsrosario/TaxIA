package com.knowledgeflow.pgtest;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;

/**
 * Builds the disposable pgvector PostgreSQL containers used by the integration tests.
 *
 * <p>Testcontainers' default {@link PostgreSQLContainer} wait strategy only watches the log for
 * the second "database system is ready to accept connections" line. It never checks that the
 * host-mapped port accepts connections, so under load a test could connect before Docker's port
 * forwarding was ready and fail with "Connection refused". This helper waits for both the log
 * message and the listening port.
 *
 * <p>Containers built here are ephemeral; a database name such as {@code knowledgeflow_pilot}
 * passed via {@code withDatabaseName(...)} only names the database inside the throwaway container.
 */
public final class PgContainers {

    public static final String PGVECTOR_IMAGE = "pgvector/pgvector:pg16";

    private PgContainers() {}

    /** A pgvector container that is ready only when the log says so and the mapped port listens. */
    public static PostgreSQLContainer<?> pgvector() {
        return new PostgreSQLContainer<>(PGVECTOR_IMAGE).waitingFor(logAndPort());
    }

    static WaitAllStrategy logAndPort() {
        return new WaitAllStrategy()
                .withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
                .withStrategy(Wait.forListeningPort());
    }
}
