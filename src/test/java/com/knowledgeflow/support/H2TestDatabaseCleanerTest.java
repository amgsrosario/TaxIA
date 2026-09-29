package com.knowledgeflow.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Fail-closed guard of {@link H2TestDatabaseCleaner}: no Spring context, no database — the
 * connection metadata is mocked so the refusal paths can be proven without a real PostgreSQL.
 */
class H2TestDatabaseCleanerTest {

    @Test
    void refusesPostgresPilotDatabase() throws SQLException {
        Connection connection = connectionFor("PostgreSQL",
                "jdbc:postgresql://localhost:15432/knowledgeflow_pilot");

        assertThatThrownBy(() -> H2TestDatabaseCleaner.clean(dataSourceFor(connection)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refuses");
        verify(connection, never()).createStatement();
    }

    @Test
    void refusesFileBasedH2Database() throws SQLException {
        Connection connection = connectionFor("H2", "jdbc:h2:file:./data/knowledgeflow");

        assertThatThrownBy(() -> H2TestDatabaseCleaner.clean(dataSourceFor(connection)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refuses");
        verify(connection, never()).createStatement();
    }

    @Test
    void refusesToRunInsideActiveTransaction() {
        DataSource dataSource = mock(DataSource.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> H2TestDatabaseCleaner.clean(dataSource))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("transaction");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(dataSource);
    }

    private static Connection connectionFor(String product, String url) throws SQLException {
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getDatabaseProductName()).thenReturn(product);
        when(metaData.getURL()).thenReturn(url);
        Connection connection = mock(Connection.class);
        when(connection.getMetaData()).thenReturn(metaData);
        return connection;
    }

    private static DataSource dataSourceFor(Connection connection) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        return dataSource;
    }
}
