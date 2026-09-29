package com.knowledgeflow.support;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Test-only reset of the shared in-memory H2 database used by the {@code test} profile.
 *
 * <p>Every {@code @SpringBootTest} under the {@code test} profile points at the same
 * {@code jdbc:h2:mem:knowledgeflow} database, which lives for the whole Surefire fork. Classes that
 * commit data must therefore start from a known state regardless of which class ran before them.
 * Hand-written {@code deleteAll()} lists per class drift apart as the schema grows, and a row left
 * in a table that a list does not know about blocks deletion of its parent through a foreign key.
 * This cleaner empties every base table instead, so it keeps working when tables are added.
 *
 * <p>Safety:
 * <ul>
 *   <li>Fail-closed: it refuses to run unless the connection is an in-memory H2 database
 *       ({@code jdbc:h2:mem:}), so it can never touch PostgreSQL or {@code knowledgeflow_pilot}.</li>
 *   <li>It refuses to run inside an active Spring transaction: it uses its own connection, and a
 *       truncate issued beside an open test transaction would not be isolated from it.</li>
 *   <li>Referential integrity is switched off only for the duration of the truncates and is always
 *       switched back on in {@code finally}. The setting is database-wide in H2, which is safe here
 *       because Surefire runs test classes sequentially in the fork.</li>
 * </ul>
 *
 * <p>Reference data: {@code roles} is preserved. In PostgreSQL it is a fixed catalogue seeded by
 * Flyway (V2); in H2 it starts empty and the tests that need a role seed it themselves
 * idempotently ({@code findByName(...).orElseGet(save)}). No test mutates it otherwise.
 */
public final class H2TestDatabaseCleaner {

    static final Set<String> PRESERVED_TABLES = Set.of("roles");

    private static final String IN_MEMORY_H2_URL_PREFIX = "jdbc:h2:mem:";

    private H2TestDatabaseCleaner() {
    }

    public static void clean(DataSource dataSource) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "H2TestDatabaseCleaner must not run inside an active transaction");
        }
        try (Connection connection = dataSource.getConnection()) {
            assertInMemoryH2(connection.getMetaData());
            truncateMutableTables(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clean the H2 test database", e);
        }
    }

    static void assertInMemoryH2(DatabaseMetaData metaData) throws SQLException {
        String product = metaData.getDatabaseProductName();
        String url = metaData.getURL();
        if (!"H2".equals(product) || url == null || !url.startsWith(IN_MEMORY_H2_URL_PREFIX)) {
            throw new IllegalStateException(
                    "H2TestDatabaseCleaner refuses to run against " + product + " (" + url + ")");
        }
    }

    private static void truncateMutableTables(Connection connection) throws SQLException {
        List<String> tables = mutableTables(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET REFERENTIAL_INTEGRITY FALSE");
            try {
                for (String table : tables) {
                    statement.execute("TRUNCATE TABLE \"" + table + "\"");
                }
            } finally {
                statement.execute("SET REFERENTIAL_INTEGRITY TRUE");
            }
        }
    }

    private static List<String> mutableTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                                + "WHERE TABLE_SCHEMA = SCHEMA() AND TABLE_TYPE = 'BASE TABLE'")) {
            while (rs.next()) {
                String table = rs.getString(1);
                if (!PRESERVED_TABLES.contains(table.toLowerCase(Locale.ROOT))) {
                    tables.add(table);
                }
            }
        }
        return tables;
    }
}
