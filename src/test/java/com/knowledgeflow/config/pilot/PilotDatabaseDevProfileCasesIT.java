package com.knowledgeflow.config.pilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.knowledgeflow.pgtest.PgContainers;
import com.knowledgeflow.rag.EmbeddingService;
import com.knowledgeflow.rag.RagSearchService;
import com.knowledgeflow.rag.ValidatedCasesPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
    @Autowired RagSearchService ragSearch;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EmbeddingService embeddingService;

    @Test
    void casesStayExcludedFromTheRealSearch() {
        UUID org = UUID.randomUUID();
        UUID client = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        List<Float> vector = new ArrayList<>(Collections.nCopies(768, 0f));
        vector.set(0, 1f);
        when(embeddingService.embedQuery(anyString())).thenReturn(vector);
        jdbc.update("INSERT INTO organizations(id, name, created_at, updated_at) VALUES (?, 'Org', NOW(), NOW())", org);
        jdbc.update("INSERT INTO clients(id, organization_id, name, status, created_at, updated_at) "
                + "VALUES (?, ?, 'Cliente', 'ACTIVE', NOW(), NOW())", client, org);
        jdbc.update("INSERT INTO knowledge_cases(id, organization_id, client_id, title, question, content, status, "
                + "current_version_number, created_at, updated_at) VALUES (?, ?, ?, 't', 'p', 'c', 'VALIDATED', 1, NOW(), NOW())",
                caseId, org, client);
        jdbc.update("INSERT INTO knowledge_case_embeddings(id, knowledge_case_id, chunk_text, embedding, created_at) "
                + "VALUES (?, ?, 'x', ?::vector, NOW())", UUID.randomUUID(), caseId, vector.toString());

        assertThat(policy.included()).isFalse();
        assertThat(ragSearch.findSimilar(org, "p")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM knowledge_cases WHERE id = ?", String.class, caseId))
                .isEqualTo("VALIDATED");
    }
}
