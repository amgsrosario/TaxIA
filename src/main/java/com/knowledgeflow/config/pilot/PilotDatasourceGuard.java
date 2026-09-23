package com.knowledgeflow.config.pilot;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fail-closed startup guard for the TaxIA <b>pilot</b> profile — Bloco E,
 * E9C-pilot-profile (PROMPT 90).
 *
 * <p>Frase-mestra: "Sob o profile pilot, a aplicação só arranca se o datasource for
 * inequivocamente a {@code knowledgeflow_pilot} num host loopback — nunca a
 * {@code knowledgeflow}, nunca uma base de teste, nunca um nome inesperado."
 *
 * <p>This bean exists only under the {@code pilot} profile (so it never affects dev, test
 * or pgtest). At startup it opens a single <b>read-only</b> connection, reads the resolved
 * database name and JDBC host from the connection metadata, and refuses to complete startup
 * (throws, aborting the context) unless:
 * <ul>
 *   <li>the database name is exactly {@code knowledgeflow_pilot}; and</li>
 *   <li>the JDBC host is a loopback host ({@code localhost} / {@code 127.0.0.1}).</li>
 * </ul>
 * The check performs no writes: it only reads {@link Connection#getCatalog()} and
 * {@link java.sql.DatabaseMetaData#getURL()} on a read-only connection.
 *
 * <p><b>Not a runner.</b> This is a configuration guard, deliberately named to avoid any
 * confusion with the future governed E9C runner. It never publishes, indexes, rolls back,
 * provisions actors, or writes any data.
 */
@Component
@Profile("pilot")
public class PilotDatasourceGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(PilotDatasourceGuard.class);

    /** The only database the pilot profile is ever allowed to run against. */
    static final String EXPECTED_DATABASE = "knowledgeflow_pilot";

    /** The non-pilot database that must never be touched under the pilot profile. */
    static final String FORBIDDEN_DEV_DATABASE = "knowledgeflow";

    /** Loopback hosts permitted for the pilot datasource. */
    static final Set<String> ALLOWED_HOSTS = Set.of("localhost", "127.0.0.1");

    private final DataSource dataSource;

    public PilotDatasourceGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        validate(dataSource);
    }

    /**
     * Validates the datasource, throwing {@link IllegalStateException} (which aborts startup)
     * when it does not point unambiguously at {@code knowledgeflow_pilot} on a loopback host.
     * Read-only: opens a read-only connection and reads metadata only.
     *
     * <p>Public so the guarded one-shot pilot runner (Bloco E, E9C) can reuse the exact same
     * fail-closed base check before any read or write, without duplicating the logic.
     */
    public static void validate(DataSource dataSource) {
        String database;
        String url;
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            database = connection.getCatalog();
            url = connection.getMetaData().getURL();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "TaxIA pilot datasource guard: could not inspect the datasource to verify it is "
                            + EXPECTED_DATABASE, e);
        }

        String normalized = database == null ? "" : database.trim().toLowerCase(Locale.ROOT);

        if (FORBIDDEN_DEV_DATABASE.equals(normalized)) {
            throw new IllegalStateException(
                    "TaxIA pilot datasource guard: refusing to start — the pilot profile is pointed at "
                            + "the non-pilot development database '" + FORBIDDEN_DEV_DATABASE + "'. "
                            + "It must point at '" + EXPECTED_DATABASE + "'. Set SPRING_DATASOURCE_URL "
                            + "to the pilot database.");
        }
        if (!EXPECTED_DATABASE.equals(normalized)) {
            throw new IllegalStateException(
                    "TaxIA pilot datasource guard: refusing to start — expected database '"
                            + EXPECTED_DATABASE + "' but the datasource resolves to '" + database + "'.");
        }

        String host = extractHost(url);
        if (host != null && !ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "TaxIA pilot datasource guard: refusing to start — the pilot database must be on a "
                            + "loopback host " + ALLOWED_HOSTS + " but the datasource host is '" + host + "'.");
        }

        log.info("PilotDatasourceGuard OK — database '{}' on loopback host '{}' accepted for pilot profile.",
                database, host);
    }

    /** Best-effort extraction of the host from a {@code jdbc:postgresql://host:port/db} URL. */
    static String extractHost(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            return null;
        }
        try {
            URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
            return uri.getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
