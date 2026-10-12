package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.ai.documented.FoundationGovernance;
import com.knowledgeflow.ai.documented.FoundationGovernanceResolver;
import com.knowledgeflow.ai.grounding.GroundingFoundation;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.repository.KnowledgeQaGovernanceRow;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * M4B (ADR-007) sobre PostgreSQL real: a query nativa de governança devolve o id (uuid → texto), o
 * risco em bruto e a validação humana; o resolver agrega pelo mais restritivo e trata um risco
 * ausente (coluna nula, permitida pelo schema) como desconhecido. Base descartável (Testcontainers).
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
class FoundationGovernancePostgresIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired private FoundationGovernanceResolver resolver;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("Query nativa e agregação em PostgreSQL: MEDIUM/true + LOW/false → MEDIUM, validação humana true")
    void nativeQueryAndAggregation() {
        Organization org = organizationRepository.save(new Organization("Org M4B PG", null));
        KnowledgeQuestionAnswer medium = curated(org, "M4B-PG-1", KnowledgeRiskLevel.MEDIUM, true);
        KnowledgeQuestionAnswer low = curated(org, "M4B-PG-2", KnowledgeRiskLevel.LOW, false);

        List<KnowledgeQaGovernanceRow> rows = qaRepository.findGovernanceRowsByIdIn(org.getId(), Set.of(medium.getId(), low.getId()));
        assertThat(rows).extracting(r -> UUID.fromString(r.getId())).containsExactlyInAnyOrder(medium.getId(), low.getId());
        assertThat(rows).filteredOn(r -> UUID.fromString(r.getId()).equals(medium.getId())).singleElement()
                .satisfies(r -> {
                    assertThat(r.getRiskLevel()).isEqualTo("MEDIUM");
                    assertThat(r.getRequiresHumanValidation()).isTrue();
                });

        FoundationGovernance governance = resolver.resolve(org.getId(), List.of(
                new GroundingFoundation(medium.getId(), "M"), new GroundingFoundation(low.getId(), "L")));
        assertThat(governance.aggregatedRiskLevel()).isEqualTo(KnowledgeRiskLevel.MEDIUM);
        assertThat(governance.requiresHumanValidation()).isTrue();
        assertThat(governance.riskUnknown()).isFalse();
    }

    @Test
    @DisplayName("risk_level nulo em PostgreSQL → RISK_UNKNOWN, decisão como HIGH")
    void nullRiskIsUnknown() {
        Organization org = organizationRepository.save(new Organization("Org M4B PG null", null));
        KnowledgeQuestionAnswer qa = curated(org, "M4B-PG-3", KnowledgeRiskLevel.LOW, false);
        jdbc.update("UPDATE knowledge_question_answers SET risk_level = NULL WHERE id = ?", qa.getId());

        FoundationGovernance governance = resolver.resolve(org.getId(), List.of(new GroundingFoundation(qa.getId(), "N")));
        assertThat(governance.riskUnknown()).isTrue();
        assertThat(governance.aggregatedRiskLevel()).isNull();
        assertThat(governance.decisionRiskLevel()).isEqualTo(KnowledgeRiskLevel.HIGH);
    }

    private KnowledgeQuestionAnswer curated(Organization org, String key, KnowledgeRiskLevel risk, boolean rhv) {
        KnowledgeQuestionAnswer qa = new KnowledgeQuestionAnswer(org, "Pergunta " + key + "?", "Resposta.", "m4b-pg", key);
        qa.updateCuration(null, null, null, KnowledgeTopic.IVA, null, "PT", risk, rhv, null, null, null);
        return qaRepository.save(qa);
    }
}
