package com.knowledgeflow.common.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reports whether the pgvector extension is installed in the connected
 * PostgreSQL. Degrades readiness without affecting liveness. Excluded from the
 * H2-backed "test" profile where the extension does not exist.
 */
@Component("pgvector")
@Profile("!test")
public class PgVectorHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;

    public PgVectorHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        try {
            var versions = jdbcTemplate.queryForList(
                    "SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class);
            if (versions.isEmpty()) {
                return Health.down().withDetail("reason", "pgvector extension not installed").build();
            }
            return Health.up().withDetail("version", versions.get(0)).build();
        } catch (Exception e) {
            return Health.down().withDetail("reason", e.getClass().getSimpleName()).build();
        }
    }
}
