package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.repository.AuditEventRepository;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.knowledge.dto.SourceReferenceRequest;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.rag.KnowledgeQaEmbeddingIndexer;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerCurationService;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerPublicationService;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Transactional failure tests over real PostgreSQL + pgvector.
 *
 * Proves that publication, versioning and archival are atomic:
 *   TC-TX-01: embedding failure does not leave a partial publication;
 *   TC-TX-02: duplicate publication does not create a duplicate embedding;
 *   TC-TX-03: failed new version keeps the previous version active (full rollback);
 *   TC-TX-04: failed archive destroys neither state, sources nor audit history.
 *
 * Zero external calls: the indexer writes via JDBC inside the service transaction,
 * with switchable failure injection.
 *
 * Run: mvn verify -Ppgtest -Dit.test=KnowledgeQaTransactionalRollbackPostgresIT
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(KnowledgeQaTransactionalRollbackPostgresIT.FailInjectionConfig.class)
class KnowledgeQaTransactionalRollbackPostgresIT {

    // =========================================================================
    // @TestConfiguration: JDBC indexer with switchable failure injection.
    // index()/remove() first perform the real write and only then throw, so a
    // failure exercises rollback of work already done inside the transaction.
    // =========================================================================

    @TestConfiguration
    static class FailInjectionConfig {

        static final int DIM = 768;
        static final String VEC;
        static final AtomicBoolean FAIL_ON_INDEX = new AtomicBoolean(false);
        static final AtomicBoolean FAIL_ON_REMOVE = new AtomicBoolean(false);

        static {
            StringBuilder sb = new StringBuilder("[1.0");
            for (int i = 1; i < DIM; i++) sb.append(",0.0");
            VEC = sb.append(']').toString();
        }

        @Bean
        @Primary
        KnowledgeQaEmbeddingIndexer failInjectingIndexer(JdbcTemplate jdbc) {
            return new KnowledgeQaEmbeddingIndexer() {
                @Override
                public void index(UUID qaId, String question, String answer, String topic) {
                    jdbc.update("""
                            INSERT INTO knowledge_qa_embeddings (knowledge_qa_id, embedding)
                            VALUES (?::uuid, ?::vector)
                            ON CONFLICT (knowledge_qa_id) DO UPDATE
                              SET embedding = EXCLUDED.embedding
                            """, qaId.toString(), VEC);
                    if (FAIL_ON_INDEX.get()) {
                        throw new RuntimeException("Falha simulada de embedding (TC-TX)");
                    }
                }

                @Override
                public void remove(UUID qaId) {
                    jdbc.update(
                            "DELETE FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                            qaId.toString());
                    if (FAIL_ON_REMOVE.get()) {
                        throw new RuntimeException("Falha simulada na remocao de embedding (TC-TX)");
                    }
                }
            };
        }
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeQuestionAnswerCurationService curationService;
    @Autowired KnowledgeQuestionAnswerPublicationService publicationService;
    @Autowired KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired AuditEventRepository auditRepository;

    private static final UUID ORG_ID  = UUID.fromString("f1000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("f1000000-0000-0000-0000-000000000010");
    private static final String SOURCE_SYSTEM = "TX_TEST";

    private Organization org;

    @BeforeAll
    void insertOrgAndUser() {
        jdbc.update(
                "INSERT INTO organizations(id, name, tax_identifier, created_at, updated_at)"
                + " VALUES (?,?,?,NOW(),NOW())",
                ORG_ID, "Org Rollback E2E", "PT900000099");
        jdbc.update(
                "INSERT INTO users(id, email, full_name, password_hash, status, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,NOW(),NOW())",
                USER_ID, "admin-tx@e2e.test", "Admin TX E2E", "$2a$10$dummyhashTx", "ACTIVE");
    }

    @BeforeEach
    void resetFailureInjection() {
        FailInjectionConfig.FAIL_ON_INDEX.set(false);
        FailInjectionConfig.FAIL_ON_REMOVE.set(false);
        org = organizationRepository.findById(ORG_ID).orElseThrow();
    }

    // =========================================================================
    // TC-TX-01 — Falha de embedding nao deixa publicacao parcial
    // =========================================================================

    @Test
    @DisplayName("TC-TX-01: Falha do indexer durante publish faz rollback total (sem publishedAt, sem embedding, sem auditoria)")
    void embeddingFailureRollsBackPublication() {
        UUID qaId = createValidatedQaWithSource("TX-01");

        FailInjectionConfig.FAIL_ON_INDEX.set(true);
        assertThatThrownBy(() ->
                publicationService.publish(ORG_ID, USER_ID, "Editor TX", qaId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Falha simulada");

        KnowledgeQuestionAnswer qa = qaRepository.findById(qaId).orElseThrow();
        assertThat(qa.isPublished()).isFalse();
        assertThat(qa.getPublishedAt()).isNull();
        assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);

        // O INSERT do embedding foi executado antes da falha — o rollback removeu-o
        assertThat(embeddingCount(qaId)).isZero();

        // Nenhum evento de publicacao ficou registado
        assertThat(auditActions(qaId)).doesNotContain(AuditAction.KNOWLEDGE_QA_PUBLISHED);
    }

    // =========================================================================
    // TC-TX-02 — Publicacao duplicada nao cria embedding duplicado
    // =========================================================================

    @Test
    @DisplayName("TC-TX-02: Segunda publicacao e rejeitada com CONFLICT e mantem exactamente 1 embedding")
    void duplicatePublicationDoesNotDuplicateEmbedding() {
        UUID qaId = createValidatedQaWithSource("TX-02");

        publicationService.publish(ORG_ID, USER_ID, "Editor TX", qaId);
        assertThat(embeddingCount(qaId)).isEqualTo(1);

        assertThatThrownBy(() ->
                publicationService.publish(ORG_ID, USER_ID, "Editor TX", qaId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already published");

        assertThat(embeddingCount(qaId)).isEqualTo(1);
        long publishedEvents = auditActions(qaId).stream()
                .filter(a -> a == AuditAction.KNOWLEDGE_QA_PUBLISHED)
                .count();
        assertThat(publishedEvents).isEqualTo(1);
    }

    // =========================================================================
    // TC-TX-03 — Nova versao falhada mantem a anterior activa
    // =========================================================================

    @Test
    @DisplayName("TC-TX-03: createNewVersion que falha a meio da transacao mantem V1 publicada, com embedding, sem V2")
    void failedNewVersionKeepsPreviousActive() {
        // O indexer apaga o embedding de V1 e falha logo a seguir, dentro da
        // transacao de createNewVersion — o rollback tem de repor o embedding.
        UUID qaId = createValidatedQaWithSource("TX-03");
        publicationService.publish(ORG_ID, USER_ID, "Editor TX", qaId);
        assertThat(embeddingCount(qaId)).isEqualTo(1);
        long qaCountBefore = totalQaCount();

        FailInjectionConfig.FAIL_ON_REMOVE.set(true);
        assertThatThrownBy(() ->
                publicationService.createNewVersion(
                        ORG_ID, USER_ID, "Editor TX", qaId, "Resposta ficticia v2."))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Falha simulada");
        FailInjectionConfig.FAIL_ON_REMOVE.set(false);

        // Rollback total: V1 continua publicada, embedding reposto, nenhuma V2 criada
        KnowledgeQuestionAnswer v1 = qaRepository.findById(qaId).orElseThrow();
        assertThat(v1.isPublished()).isTrue();
        assertThat(v1.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
        assertThat(embeddingCount(qaId)).isEqualTo(1);
        assertThat(totalQaCount()).isEqualTo(qaCountBefore);
        assertThat(auditActions(qaId)).doesNotContain(AuditAction.KNOWLEDGE_QA_VERSION_CREATED);
    }

    // =========================================================================
    // TC-TX-04 — Arquivo falhado nao destroi historico
    // =========================================================================

    @Test
    @DisplayName("TC-TX-04: Arquivo em estado invalido e bloqueado sem destruir estado, fontes, embedding nem auditoria")
    void failedArchiveDestroysNothing() {
        UUID qaId = createValidatedQaWithSource("TX-04");
        publicationService.publish(ORG_ID, USER_ID, "Editor TX", qaId);

        long sourcesBefore = sourceCount(qaId);
        long auditBefore = auditActions(qaId).size();

        // VALIDATED nao e estado de partida valido para ARCHIVED
        assertThatThrownBy(() ->
                curationService.archive(ORG_ID, USER_ID, qaId))
                .isInstanceOf(BusinessException.class);

        KnowledgeQuestionAnswer qa = qaRepository.findById(qaId).orElseThrow();
        assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
        assertThat(qa.isPublished()).isTrue();
        assertThat(embeddingCount(qaId)).isEqualTo(1);
        assertThat(sourceCount(qaId)).isEqualTo(sourcesBefore);
        assertThat((long) auditActions(qaId).size()).isEqualTo(auditBefore);
        assertThat(auditActions(qaId)).doesNotContain(AuditAction.KNOWLEDGE_QA_ARCHIVED);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Cria um QA ficticio VALIDATED com fonte associada, pronto a publicar. */
    private UUID createValidatedQaWithSource(String externalKey) {
        var qa = new KnowledgeQuestionAnswer(org,
                "Pergunta ficticia de rollback " + externalKey.substring(0, Math.min(5, externalKey.length())) + "?",
                "Resposta ficticia original.",
                SOURCE_SYSTEM, externalKey);
        qa.updateCuration(null, "Resposta ficticia curada.", "Resposta ficticia tecnica.",
                KnowledgeTopic.OUTROS, null, "PT",
                KnowledgeRiskLevel.LOW, false, null, null, null);
        qa.markPendingReview();
        qa.validate("Revisor TX");
        qa = qaRepository.save(qa);

        curationService.addSource(ORG_ID, USER_ID, qa.getId(), new SourceReferenceRequest(
                KnowledgeSourceType.INTERNAL_OPINION, "Documento Ficticio TX",
                "TX-REF", null, null, null, null, null, null));
        return qa.getId();
    }

    private long embeddingCount(UUID qaId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                Long.class, qaId.toString());
        return n != null ? n : 0L;
    }

    private long sourceCount(UUID qaId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_source_references WHERE knowledge_qa_id = ?::uuid",
                Long.class, qaId.toString());
        return n != null ? n : 0L;
    }

    private long totalQaCount() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_question_answers WHERE organization_id = ?::uuid",
                Long.class, ORG_ID.toString());
        return n != null ? n : 0L;
    }

    private List<AuditAction> auditActions(UUID qaId) {
        return auditRepository
                .findByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
                        ORG_ID, "KnowledgeQuestionAnswer", qaId)
                .stream()
                .map(e -> e.getAction())
                .toList();
    }
}
