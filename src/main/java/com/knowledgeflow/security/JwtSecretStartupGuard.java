package com.knowledgeflow.security;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail-closed startup guard for the JWT signing secret (ADR-006).
 *
 * <p>The application refuses to start when {@code KNOWLEDGEFLOW_JWT_SECRET} is missing, is the
 * public development default, is shorter than 32 bytes or is visibly non-random. The guard applies
 * to every profile except the automated test profiles ({@code test}, {@code pgtest}), and it
 * applies <b>always</b> when the datasource is {@code knowledgeflow_pilot}, whatever the profile
 * (including {@code dev} and the test profiles). The secret value is never logged or included in
 * an error message.
 *
 * <p>The JWT encoder and decoder beans depend on this guard, so no token can be signed or accepted
 * before the check has passed.
 */
@Component
public class JwtSecretStartupGuard {

    private static final Logger log = LoggerFactory.getLogger(JwtSecretStartupGuard.class);

    static final Set<String> TEST_PROFILES = Set.of("test", "pgtest");
    static final String PILOT_DATABASE = "knowledgeflow_pilot";

    public JwtSecretStartupGuard(JwtProperties jwtProperties, Environment environment,
                                 ObjectProvider<DataSource> dataSource) {
        boolean testProfile = Arrays.stream(environment.getActiveProfiles()).anyMatch(TEST_PROFILES::contains);
        boolean pilotDatabase = testProfile && pointsAtPilot(dataSource.getIfAvailable());
        boolean enforced = !testProfile || pilotDatabase;
        if (!enforced) {
            log.info("JWT secret guard: automated test profile on a non-pilot database; policy not enforced.");
            return;
        }
        // Public test keys: only the automated test profiles, and on a database named
        // knowledgeflow_pilot only the disposable Testcontainers runs of pgtest.
        boolean allowTestKeys = testProfile && (!pilotDatabase
                || Arrays.asList(environment.getActiveProfiles()).contains("pgtest"));
        JwtSecretPolicy.violation(jwtProperties.secret(), allowTestKeys).ifPresent(reason -> {
            throw new IllegalStateException("JWT secret guard: refusing to start — " + reason
                    + ". Generate a random secret of at least " + JwtSecretPolicy.MIN_BYTES
                    + " bytes (e.g. openssl rand -base64 48) and set it only in the local environment.");
        });
        log.info("JWT secret guard OK — signing secret satisfies the policy.");
    }

    /** Read-only metadata check: is the datasource the pilot database? */
    static boolean pointsAtPilot(DataSource dataSource) {
        if (dataSource == null) {
            return false;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            String catalog = connection.getCatalog();
            return catalog != null && PILOT_DATABASE.equals(catalog.trim().toLowerCase(Locale.ROOT));
        } catch (SQLException e) {
            // Unknown database: fail closed by enforcing the policy.
            return true;
        }
    }
}
