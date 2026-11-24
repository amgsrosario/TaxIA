package com.knowledgeflow.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class ValidatedCasesPolicyTest {

    @Test
    void includedOnlyWhenEnabledOnAKnownNonPilotDatabase() throws Exception {
        assertThat(policy(true, "knowledgeflow")).isTrue();
        assertThat(policy(false, "knowledgeflow")).isFalse();
        assertThat(policy(true, "knowledgeflow_pilot")).isFalse();
        assertThat(policy(true, " KnowledgeFlow_Pilot ")).isFalse();
        assertThat(policy(true, null)).isFalse();
        assertThat(policy(true, " ")).isFalse();
    }

    @Test
    void failsClosedWhenTheDatabaseCannotBeIdentified() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("indisponível"));
        assertThat(new ValidatedCasesPolicy(true, ds).included()).isFalse();
    }

    @Test
    void pilotProfileTurnsCasesOffWithoutAnEnvironmentOverride() throws Exception {
        Object value = new YamlPropertySourceLoader().load("pilot", new ClassPathResource("application-pilot.yml"))
                .get(0).getProperty("knowledgeflow.rag.validated-cases-enabled");
        assertThat(value).isEqualTo(false);
    }

    private static boolean policy(boolean enabled, String catalog) throws SQLException {
        Connection c = mock(Connection.class);
        when(c.getCatalog()).thenReturn(catalog);
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(c);
        return new ValidatedCasesPolicy(enabled, ds).included();
    }
}
