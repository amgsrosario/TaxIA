package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.ai.documented.FreshnessStatus;
import com.knowledgeflow.knowledge.dto.SourceReferenceResponse;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRow;
import com.knowledgeflow.knowledge.service.SourceFreshnessCurationService;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * C8 / ADR-008 sobre PostgreSQL real (Flyway até V18, Hibernate validate): colunas de verificação,
 * FK ao utilizador, CHECKs de coerência (verificação com responsável, consolidada só com verificação,
 * matéria estável justificada), serviço governado e leitura pelo pipeline (query da linha da fonte).
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourceFreshnessPostgresIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final UUID CURATOR = UUID.fromString("c8000000-0000-0000-0000-000000000001");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired private KnowledgeSourceReferenceRepository sourceRepository;
    @Autowired private SourceFreshnessCurationService service;

    @BeforeAll
    void users() {
        jdbc.update("INSERT INTO users(id, email, full_name, password_hash, status, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,NOW(),NOW())", CURATOR, "curador@c8.test", "Curador", "$2a$10$dummy", "ACTIVE");
    }

    private KnowledgeSourceReference newSource(Organization org, String key, KnowledgeSourceType type) {
        KnowledgeQuestionAnswer qa = qaRepository.save(new KnowledgeQuestionAnswer(org, "Pergunta " + key + "?", "R.", "c8-pg", key));
        KnowledgeSourceReference s = new KnowledgeSourceReference(qa, type, "Fonte " + key);
        s.update(type, "Fonte " + key, null, "https://exemplo.pt/" + key, null, null, null, null, null);
        return sourceRepository.save(s);
    }

    @Test
    @DisplayName("V18: colunas e CHECKs presentes; fontes existentes sem verificação (sem backfill)")
    void migrationShape() {
        Integer version = jdbc.queryForObject(
                "SELECT max(version::int) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(version).isGreaterThanOrEqualTo(18);
        List<String> columns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns WHERE table_name = 'knowledge_source_references'
                """, String.class);
        assertThat(columns).contains("verified_at", "verified_by", "consolidated_version_confirmed", "stable_matter",
                "stable_matter_reason", "stable_matter_marked_at", "stable_matter_marked_by");
        List<String> checks = jdbc.queryForList("""
                SELECT conname FROM pg_constraint WHERE conrelid = 'knowledge_source_references'::regclass AND contype = 'c'
                """, String.class);
        assertThat(checks).contains("ck_ksr_verification_complete", "ck_ksr_consolidated_needs_verification",
                "ck_ksr_stable_matter_justified");
    }

    @Test
    @DisplayName("CHECKs rejeitam verificação sem responsável, consolidada sem verificação e estável sem justificação; FK do utilizador")
    void constraintsRejectIncoherentRows() {
        Organization org = organizationRepository.save(new Organization("Org C8 PG ck", null));
        KnowledgeSourceReference s = newSource(org, "C8PG-1", KnowledgeSourceType.LEGISLATION);
        assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_source_references SET verified_at = NOW() WHERE id = ?", s.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE knowledge_source_references SET consolidated_version_confirmed = TRUE WHERE id = ?", s.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_source_references SET stable_matter = TRUE WHERE id = ?", s.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE knowledge_source_references SET verified_at = NOW(), verified_by = ? WHERE id = ?", UUID.randomUUID(), s.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Serviço governado em PostgreSQL: verificação → CURRENT; leitura pelo pipeline traz os factos governados")
    void serviceAndPipelineRead() {
        Organization org = organizationRepository.save(new Organization("Org C8 PG svc", null));
        KnowledgeSourceReference s = newSource(org, "C8PG-2", KnowledgeSourceType.LEGISLATION);
        UUID qaId = s.getQuestionAnswer().getId();

        SourceReferenceResponse r = service.verify(org.getId(), CURATOR, qaId, s.getId(), true);
        assertThat(r.freshnessStatus()).isEqualTo(FreshnessStatus.CURRENT);
        assertThat(r.verifiedBy()).isEqualTo(CURATOR);

        SourceReferenceResponse stable = service.setStableMatter(org.getId(), CURATOR, qaId, s.getId(), true, "Regra estável.");
        assertThat(stable.stableMatter()).isTrue();

        KnowledgeSourceReferenceRow row = sourceRepository.findRowsByQuestionAnswerIdIn(Set.of(qaId)).get(0);
        assertThat(row.verifiedBy()).isEqualTo(CURATOR);
        assertThat(row.verifiedAt()).isNotNull();
        assertThat(row.consolidatedVersionConfirmed()).isTrue();
        assertThat(row.stableMatter()).isTrue();
        assertThat(row.stableMatterMarkedBy()).isEqualTo(CURATOR);
    }
}
