package com.knowledgeflow.config.pilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.pgtest.PgContainers;
import com.knowledgeflow.rag.ValidatedCasesPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** M0: perfil {@code dev} (o do launcher) com a propriedade ligada, sobre uma base descartável chamada knowledgeflow_pilot. */
@SpringBootTest(properties = {
        "knowledgeflow.rag.validated-cases-enabled=true",
        "knowledgeflow.security.jwt.secret=pilot-it-only-fictitious-jwt-signing-key-0123456789"
})
@ActiveProfiles("dev")
@Testcontainers
class PilotDatabaseDevProfileCasesIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = PgContainers.pgvector().withDatabaseName("knowledgeflow_pilot");

    @Autowired ValidatedCasesPolicy policy;

    @Test
    void casesStayExcluded() {
        assertThat(policy.included()).isFalse();
    }
}
