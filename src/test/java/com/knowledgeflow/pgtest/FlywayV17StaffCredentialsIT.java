package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V16 → V17 (ADR-006) on real, disposable PostgreSQL: additive, legacy users (human and non-login
 * service account) keep every value and get token_version=0 / must_change_password=false; both
 * columns are NOT NULL with defaults. Run: mvn verify -Ppgtest -Dit.test=FlywayV17StaffCredentialsIT
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlywayV17StaffCredentialsIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final UUID ORG_ID = UUID.fromString("a1700000-0000-0000-0000-000000000001");
    private static final UUID HUMAN_ID = UUID.fromString("a1700000-0000-0000-0000-000000000002");
    private static final UUID SERVICE_ID = UUID.fromString("a1700000-0000-0000-0000-000000000003");
    private static final String HUMAN_HASH = "$2a$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyzABCDE";
    private static final String SERVICE_HASH = "LOCKED-NO-LOGIN-SERVICE-ACCOUNT";

    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeAll
    void init() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(3);
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    void close() {
        if (dataSource != null) dataSource.close();
    }

    private Flyway flyway(String target) {
        var fb = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
        if (target != null) fb = fb.target(target);
        return fb.load();
    }

    @Test
    @Order(1)
    @DisplayName("V1→V16 com um utilizador humano e uma conta de serviço legacy")
    void migrateToV16WithLegacyUsers() {
        assertThat(flyway("16").migrate().migrationsExecuted).isEqualTo(16);
        jdbc.update("INSERT INTO organizations(id, name, created_at, updated_at) VALUES (?,?,NOW(),NOW())",
                ORG_ID, "Org V17");
        jdbc.update("INSERT INTO users(id, email, full_name, password_hash, status, created_at, updated_at)"
                + " VALUES (?,?,?,?,'ACTIVE',NOW(),NOW())", HUMAN_ID, "humano@v17.test", "Humano", HUMAN_HASH);
        jdbc.update("INSERT INTO users(id, email, full_name, password_hash, status, created_at, updated_at)"
                + " VALUES (?,?,?,?,'DISABLED',NOW(),NOW())", SERVICE_ID, "svc@service.local", "Serviço", SERVICE_HASH);
        jdbc.update("INSERT INTO organization_users(id, organization_id, user_id, role_id, created_at, updated_at)"
                + " VALUES (?,?,?,'10000000-0000-0000-0000-000000000001',NOW(),NOW())",
                UUID.randomUUID(), ORG_ID, HUMAN_ID);
    }

    @Test
    @Order(2)
    @DisplayName("V17 aplica-se e os utilizadores legacy ficam com token_version=0 e must_change_password=false")
    void migrateToV17PreservesLegacyUsers() {
        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT MAX(version::int) FROM flyway_schema_history WHERE success",
                Integer.class)).isEqualTo(17);

        Map<String, Object> human = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", HUMAN_ID);
        assertThat(human.get("token_version")).isEqualTo(0);
        assertThat(human.get("must_change_password")).isEqualTo(false);
        assertThat(human.get("password_hash")).isEqualTo(HUMAN_HASH);
        assertThat(human.get("status")).isEqualTo("ACTIVE");

        Map<String, Object> service = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", SERVICE_ID);
        assertThat(service.get("token_version")).isEqualTo(0);
        assertThat(service.get("must_change_password")).isEqualTo(false);
        assertThat(service.get("password_hash")).isEqualTo(SERVICE_HASH);
        assertThat(service.get("status")).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organization_users", Integer.class)).isEqualTo(1);
    }

    @Test
    @Order(3)
    @DisplayName("colunas NOT NULL com DEFAULT 0 / FALSE e tipos int/boolean")
    void columnsAreNotNullWithDefaults() {
        Map<String, Object> tv = jdbc.queryForMap("SELECT data_type, is_nullable, column_default"
                + " FROM information_schema.columns WHERE table_name='users' AND column_name='token_version'");
        assertThat(tv.get("data_type")).isEqualTo("integer");
        assertThat(tv.get("is_nullable")).isEqualTo("NO");
        assertThat(String.valueOf(tv.get("column_default"))).isEqualTo("0");
        Map<String, Object> mc = jdbc.queryForMap("SELECT data_type, is_nullable, column_default"
                + " FROM information_schema.columns WHERE table_name='users' AND column_name='must_change_password'");
        assertThat(mc.get("data_type")).isEqualTo("boolean");
        assertThat(mc.get("is_nullable")).isEqualTo("NO");
        assertThat(String.valueOf(mc.get("column_default"))).isEqualTo("false");

        UUID fresh = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id, email, full_name, password_hash, status, created_at, updated_at)"
                + " VALUES (?,?,?,?,'ACTIVE',NOW(),NOW())", fresh, "novo@v17.test", "Novo", HUMAN_HASH);
        assertThat(jdbc.queryForObject("SELECT token_version FROM users WHERE id=?", Integer.class, fresh)).isZero();
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET token_version = NULL WHERE id = ?", fresh))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET must_change_password = NULL WHERE id = ?", fresh))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(4)
    @DisplayName("nenhuma migração pendente depois da V17 (validate limpo)")
    void validateIsClean() {
        flyway(null).validate();
        assertThat(flyway(null).info().pending()).isEmpty();
    }
}
