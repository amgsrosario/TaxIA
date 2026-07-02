package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.audit.repository.AuditEventRepository;
import com.knowledgeflow.knowledge.dto.ImportReport;
import com.knowledgeflow.knowledge.dto.KnowledgeQaCurationRequest;
import com.knowledgeflow.knowledge.dto.SourceReferenceRequest;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.rag.KnowledgeQaEmbeddingIndexer;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.service.KnowledgeBenchmarkDraftService;
import com.knowledgeflow.knowledge.service.KnowledgeQaSimilarityService;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerCurationService;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerImportService;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerPublicationService;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.rag.EmbeddingService;
import com.knowledgeflow.rag.RagSearchService;
import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import com.knowledgeflow.rag.RagSearchService.SourceKind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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
 * Cross-organization security tests over real PostgreSQL + pgvector.
 *
 * Proves at service, repository/query and RAG level (the controller layer is
 * proved separately by AdminKnowledgeQaControllerSecurityTest with MockMvc):
 *
 *   TC-SEC-01..05: cross-org read/update/validate/publish/source → NOT_FOUND
 *                  (never FORBIDDEN — existence is not revealed);
 *   TC-SEC-06..09: RAG isolation in SQL (A↔B, nonexistent org, null org);
 *   TC-SEC-10..11: embeddings injected directly stay isolated; publication in A
 *                  never touches B's embeddings;
 *   TC-SEC-12..13: similarity search and repository queries are org-scoped;
 *   TC-SEC-14:     canonical uniqueness is per-organization and cross-org
 *                  canonical change is invisible;
 *   TC-SEC-15..16: externalKey preventive validation (import + new version);
 *   TC-SEC-17:     audit records carry organizationId/userId and denied
 *                  cross-org operations leave no audit trace on the target.
 *
 * Zero external calls: stub AI providers (pgtest profile), controlled embeddings.
 *
 * Run: mvn verify -Ppgtest -Dit.test=KnowledgeQaSecurityPostgresIT
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(KnowledgeQaSecurityPostgresIT.SecurityInfraConfig.class)
class KnowledgeQaSecurityPostgresIT {

    // =========================================================================
    // Controlled embedding infrastructure (same pattern as the E2E IT)
    // =========================================================================

    @TestConfiguration
    static class SecurityInfraConfig {

        static final int DIM = 768;
        static final String VEC_HIGH;

        static {
            StringBuilder sb = new StringBuilder("[1.0");
            for (int i = 1; i < DIM; i++) sb.append(",0.0");
            VEC_HIGH = sb.append(']').toString();
        }

        private static List<Float> buildHighList() {
            Float[] arr = new Float[DIM];
            arr[0] = 1.0f;
            for (int i = 1; i < DIM; i++) arr[i] = 0.0f;
            return List.of(arr);
        }

        @Bean
        @Primary
        EmbeddingService controlledEmbeddingService() {
            List<Float> vec = buildHighList();
            return new EmbeddingService() {
                @Override public List<Float> embedQuery(String text)   { return vec; }
                @Override public List<Float> embedPassage(String text) { return vec; }
            };
        }

        @Bean
        @Primary
        KnowledgeQaEmbeddingIndexer jdbcQaEmbeddingIndexer(JdbcTemplate jdbc) {
            return new KnowledgeQaEmbeddingIndexer() {
                @Override
                public void index(UUID qaId, String question, String answer, String topic) {
                    jdbc.update("""
                            INSERT INTO knowledge_qa_embeddings (knowledge_qa_id, embedding)
                            VALUES (?::uuid, ?::vector)
                            ON CONFLICT (knowledge_qa_id) DO UPDATE
                              SET embedding = EXCLUDED.embedding
                            """, qaId.toString(), VEC_HIGH);
                }

                @Override
                public void remove(UUID qaId) {
                    jdbc.update(
                            "DELETE FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                            qaId.toString());
                }
            };
        }
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeQuestionAnswerImportService importService;
    @Autowired KnowledgeQuestionAnswerCurationService curationService;
    @Autowired KnowledgeQuestionAnswerPublicationService publicationService;
    @Autowired KnowledgeQaSimilarityService similarityService;
    @Autowired KnowledgeBenchmarkDraftService benchmarkDraftService;
    @Autowired RagSearchService ragSearchService;
    @Autowired KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired AuditEventRepository auditRepository;

    private static final UUID ORG_A   = UUID.fromString("a1000000-0000-0000-0000-000000000001");
    private static final UUID ORG_B   = UUID.fromString("b1000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN_A = UUID.fromString("a1000000-0000-0000-0000-000000000010");
    private static final UUID ADMIN_B = UUID.fromString("b1000000-0000-0000-0000-000000000010");

    private static final String SRC = "SEC_TEST";
    private static final String QUESTION_A = "Pergunta ficticia de seguranca da Org A?";
    private static final String QUESTION_B = "Pergunta ficticia de seguranca da Org B?";
    private static final String ANSWER_A = "Resposta ficticia exclusiva da Org A.";
    private static final String ANSWER_B = "Resposta ficticia exclusiva da Org B.";

    private UUID qaA;
    private UUID qaB;

    @BeforeAll
    void setUpOrgsUsersAndPublishedQas() {
        jdbc.update("INSERT INTO organizations(id, name, tax_identifier, created_at, updated_at)"
                + " VALUES (?,?,?,NOW(),NOW())", ORG_A, "Org A Seguranca", "PT910000001");
        jdbc.update("INSERT INTO organizations(id, name, tax_identifier, created_at, updated_at)"
                + " VALUES (?,?,?,NOW(),NOW())", ORG_B, "Org B Seguranca", "PT910000002");
        jdbc.update("INSERT INTO users(id, email, full_name, password_hash, status, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,NOW(),NOW())",
                ADMIN_A, "admin-a@sec.test", "Admin A Sec", "$2a$10$dummyA", "ACTIVE");
        jdbc.update("INSERT INTO users(id, email, full_name, password_hash, status, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,NOW(),NOW())",
                ADMIN_B, "admin-b@sec.test", "Admin B Sec", "$2a$10$dummyB", "ACTIVE");

        qaA = createPublishedQa(ORG_A, ADMIN_A, "SEC-A-001", QUESTION_A, ANSWER_A);
        qaB = createPublishedQa(ORG_B, ADMIN_B, "SEC-B-001", QUESTION_B, ANSWER_B);
    }

    // =========================================================================
    // TC-SEC-01..05 — acesso cruzado no service responde NOT_FOUND
    // =========================================================================

    @Test @Order(1)
    @DisplayName("TC-SEC-01: consulta cruzada (detalhe, benchmark) → NOT_FOUND, nunca FORBIDDEN")
    void crossOrgRead_notFound() {
        assertNotFoundForCrossOrg(() -> curationService.getDetail(ORG_A, qaB));
        assertNotFoundForCrossOrg(() -> benchmarkDraftService.generateDraft(ORG_A, qaB));
        assertNotFoundForCrossOrg(() -> curationService.listSources(ORG_A, qaB));
    }

    @Test @Order(2)
    @DisplayName("TC-SEC-02: alteracao cruzada (curadoria, pending-review, outdated, archive, canonical) → NOT_FOUND")
    void crossOrgWrite_notFound() {
        var req = new KnowledgeQaCurationRequest(
                null, null, null, null, null, null, null, null, null, null, null);
        assertNotFoundForCrossOrg(() -> curationService.updateCuration(ORG_A, ADMIN_A, qaB, req));
        assertNotFoundForCrossOrg(() -> curationService.markPendingReview(ORG_A, ADMIN_A, qaB));
        assertNotFoundForCrossOrg(() -> curationService.markOutdated(ORG_A, ADMIN_A, qaB));
        assertNotFoundForCrossOrg(() -> curationService.archive(ORG_A, ADMIN_A, qaB));
        assertNotFoundForCrossOrg(() -> curationService.setCanonical(ORG_A, ADMIN_A, qaB, true));
    }

    @Test @Order(3)
    @DisplayName("TC-SEC-03: validacao e rejeicao cruzadas → NOT_FOUND")
    void crossOrgValidation_notFound() {
        assertNotFoundForCrossOrg(() -> curationService.validate(ORG_A, ADMIN_A, "Intruso", qaB));
        assertNotFoundForCrossOrg(() -> curationService.reject(ORG_A, ADMIN_A, "Intruso", qaB, "x"));
    }

    @Test @Order(4)
    @DisplayName("TC-SEC-04: publicacao, despublicacao e nova versao cruzadas → NOT_FOUND")
    void crossOrgPublication_notFound() {
        assertNotFoundForCrossOrg(() -> publicationService.publish(ORG_A, ADMIN_A, "Intruso", qaB));
        assertNotFoundForCrossOrg(() -> publicationService.unpublish(ORG_A, ADMIN_A, qaB));
        assertNotFoundForCrossOrg(() -> publicationService.createNewVersion(
                ORG_A, ADMIN_A, "Intruso", qaB, "Tentativa cruzada."));
    }

    @Test @Order(5)
    @DisplayName("TC-SEC-05: fonte cruzada (ADMIN_A associa fonte a QA da Org B) → NOT_FOUND")
    void crossOrgSource_notFound() {
        var src = new SourceReferenceRequest(
                KnowledgeSourceType.OTHER, "Fonte intrusa", null, null, null, null, null, null, null);
        assertNotFoundForCrossOrg(() -> curationService.addSource(ORG_A, ADMIN_A, qaB, src));

        // O QA da Org B mantem exactamente as fontes originais
        assertThat(curationService.listSources(ORG_B, qaB)).hasSize(1);
    }

    // =========================================================================
    // TC-SEC-06..09 — RAG isolado no SQL
    // =========================================================================

    @Test @Order(6)
    @DisplayName("TC-SEC-06: RAG da Org A devolve apenas conteudo da Org A")
    void ragOrgA_onlySeesOrgA() {
        List<RetrievedCase> results = ragSearchService.findSimilar(ORG_A, QUESTION_A);
        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(r -> {
            assertThat(r.content()).doesNotContain("Org B");
            if (r.sourceKind() == SourceKind.KNOWLEDGE_QA) {
                assertThat(r.sourceQaId()).isNotEqualTo(qaB);
            }
        });
        assertThat(results.stream().map(RetrievedCase::content)).contains(ANSWER_A);
    }

    @Test @Order(7)
    @DisplayName("TC-SEC-07: RAG da Org B devolve apenas conteudo da Org B")
    void ragOrgB_onlySeesOrgB() {
        List<RetrievedCase> results = ragSearchService.findSimilar(ORG_B, QUESTION_B);
        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(r -> {
            assertThat(r.content()).doesNotContain("Org A");
            if (r.sourceKind() == SourceKind.KNOWLEDGE_QA) {
                assertThat(r.sourceQaId()).isNotEqualTo(qaA);
            }
        });
        assertThat(results.stream().map(RetrievedCase::content)).contains(ANSWER_B);
    }

    @Test @Order(8)
    @DisplayName("TC-SEC-08: organizationId inexistente devolve zero resultados")
    void ragNonexistentOrg_empty() {
        List<RetrievedCase> results =
                ragSearchService.findSimilar(UUID.randomUUID(), QUESTION_A);
        assertThat(results).isEmpty();
    }

    @Test @Order(9)
    @DisplayName("TC-SEC-09: organizationId nulo e rejeitado com VALIDATION_ERROR")
    void ragNullOrg_rejected() {
        assertThatThrownBy(() -> ragSearchService.findSimilar(null, QUESTION_A))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ApiErrorCode.VALIDATION_ERROR));
    }

    // =========================================================================
    // TC-SEC-10..11 — embeddings isolados
    // =========================================================================

    @Test @Order(10)
    @DisplayName("TC-SEC-10: embedding injectado directamente para a Org B continua invisivel para a Org A")
    void injectedEmbedding_staysIsolated() {
        // Embedding orfao (QA inexistente) — nunca recuperado (JOIN falha)
        UUID orphan = UUID.randomUUID();
        jdbc.update("INSERT INTO knowledge_question_answers "
                + "(id, organization_id, original_question, original_answer, curation_status, "
                + " requires_human_validation, canonical, version, created_at, updated_at) "
                + "VALUES (?::uuid, ?::uuid, 'Orfa?', 'Orfa.', 'IMPORTED', false, false, 0, NOW(), NOW())",
                orphan.toString(), ORG_B.toString());
        jdbc.update("INSERT INTO knowledge_qa_embeddings (knowledge_qa_id, embedding) "
                + "VALUES (?::uuid, ?::vector)", orphan.toString(), SecurityInfraConfig.VEC_HIGH);

        List<RetrievedCase> resultsA = ragSearchService.findSimilar(ORG_A, QUESTION_A);
        assertThat(resultsA).allSatisfy(r -> {
            assertThat(r.sourceQaId()).isNotEqualTo(qaB);
            assertThat(r.sourceQaId()).isNotEqualTo(orphan); // IMPORTED → excluido pelo SQL
        });
    }

    @Test @Order(11)
    @DisplayName("TC-SEC-11: publicacao na Org A nunca altera embeddings da Org B")
    void publicationInA_neverTouchesB() throws IOException {
        long bEmbeddingsBefore = countQaEmbeddings(ORG_B);

        UUID extra = createPublishedQa(ORG_A, ADMIN_A, "SEC-A-002",
                "Segunda pergunta ficticia da Org A?", "Segunda resposta ficticia da Org A.");

        assertThat(countQaEmbeddings(ORG_B)).isEqualTo(bEmbeddingsBefore);
        assertThat(countQaEmbeddings(ORG_A)).isGreaterThanOrEqualTo(2);
        assertThat(extra).isNotNull();
    }

    // =========================================================================
    // TC-SEC-12..13 — semelhantes e repository isolados
    // =========================================================================

    @Test @Order(12)
    @DisplayName("TC-SEC-12: pesquisa de semelhantes da Org A nao devolve nem revela QA da Org B")
    void similarity_isolated() {
        // A pergunta da Org B e lexicalmente quase identica a consulta — so a Org B a ve
        var resultsA = similarityService.findSimilar(ORG_A, QUESTION_B, 10);
        assertThat(resultsA).allSatisfy(r -> {
            assertThat(r.id()).isNotEqualTo(qaB);
            assertThat(r.originalQuestion()).doesNotContain("Org B");
        });

        var resultsB = similarityService.findSimilar(ORG_B, QUESTION_B, 10);
        assertThat(resultsB).anyMatch(r -> r.id().equals(qaB));
    }

    @Test @Order(13)
    @DisplayName("TC-SEC-13: queries do repository sao org-scoped (paginacao e elegiveis RAG)")
    void repositoryQueries_orgScoped() {
        var pageA = qaRepository.findByOrganizationId(
                ORG_A, org.springframework.data.domain.PageRequest.of(0, 100));
        assertThat(pageA.getContent()).allSatisfy(qa ->
                assertThat(qa.getOrganization().getId()).isEqualTo(ORG_A));
        assertThat(pageA.getContent()).noneMatch(qa -> qa.getId().equals(qaB));

        var validatedA = qaRepository.findValidatedActiveForOrg(ORG_A, java.time.LocalDate.now());
        assertThat(validatedA).allSatisfy(qa ->
                assertThat(qa.getOrganization().getId()).isEqualTo(ORG_A));
        assertThat(validatedA).noneMatch(qa -> qa.getId().equals(qaB));
    }

    // =========================================================================
    // TC-SEC-14 — canonicas por organizacao
    // =========================================================================

    @Test @Order(14)
    @DisplayName("TC-SEC-14: canonicas sao independentes por organizacao; unicidade nao cruza organizacoes")
    void canonicals_perOrganization() {
        // Ambas as organizacoes podem ter canonica no MESMO topico
        curationService.setCanonical(ORG_A, ADMIN_A, qaA, true);
        curationService.setCanonical(ORG_B, ADMIN_B, qaB, true);

        assertThat(qaRepository.findById(qaA).orElseThrow().isCanonical()).isTrue();
        assertThat(qaRepository.findById(qaB).orElseThrow().isCanonical()).isTrue();

        // ADMIN_A nao remove a canonica da Org B
        assertNotFoundForCrossOrg(() -> curationService.setCanonical(ORG_A, ADMIN_A, qaB, false));
        assertThat(qaRepository.findById(qaB).orElseThrow().isCanonical()).isTrue();
    }

    // =========================================================================
    // TC-SEC-15..16 — externalKey validada preventivamente
    // =========================================================================

    @Test @Order(15)
    @DisplayName("TC-SEC-15: importacao rejeita externalKey acima do limite (linha INVALID_ROW)")
    void importRejectsOversizedExternalKey() throws IOException {
        String longKey = "K".repeat(KnowledgeQuestionAnswer.EXTERNAL_KEY_MAX_IMPORT_LENGTH + 1);
        String csv = "externalKey,question,answer\n"
                + longKey + ",Pergunta ficticia de chave longa?,Resposta ficticia.\n";

        ImportReport report = importService.importCsv(
                ORG_A, ADMIN_A, SRC, stream(csv), false, 0);

        assertThat(report.invalid()).isEqualTo(1);
        assertThat(report.imported()).isZero();
        assertThat(report.issues()).anyMatch(i ->
                i.message() != null && i.message().contains("externalKey"));
    }

    @Test @Order(16)
    @DisplayName("TC-SEC-16: createNewVersion com chave legada demasiado longa devolve VALIDATION_ERROR controlado")
    void newVersionRejectsOversizedLegacyKey() {
        // Chave legada de 254 chars inserida directamente (contorna a validacao de import);
        // 254 + "_v2" = 257 > 255 → erro controlado ANTES de qualquer alteracao
        String legacyKey = "L".repeat(254);
        Organization orgA = organizationRepository.findById(ORG_A).orElseThrow();
        var legacy = new KnowledgeQuestionAnswer(orgA,
                "Pergunta ficticia legada?", "Resposta ficticia legada.", SRC, legacyKey);
        legacy.updateCuration(null, "Resposta curada.", "Resposta tecnica.",
                KnowledgeTopic.OUTROS, null, "PT", KnowledgeRiskLevel.LOW, false, null, null, null);
        legacy.markPendingReview();
        legacy.validate("Revisor Sec");
        legacy = qaRepository.save(legacy);
        UUID legacyId = legacy.getId();
        curationService.addSource(ORG_A, ADMIN_A, legacyId, new SourceReferenceRequest(
                KnowledgeSourceType.INTERNAL_OPINION, "Doc ficticio", null, null, null, null, null, null, null));
        publicationService.publish(ORG_A, ADMIN_A, "Editor Sec", legacyId);

        assertThatThrownBy(() -> publicationService.createNewVersion(
                ORG_A, ADMIN_A, "Editor Sec", legacyId, "Nova resposta ficticia."))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ApiErrorCode.VALIDATION_ERROR));

        // Fail-fast: nada foi alterado — V1 continua publicada com embedding
        KnowledgeQuestionAnswer unchanged = qaRepository.findById(legacyId).orElseThrow();
        assertThat(unchanged.isPublished()).isTrue();
        assertThat(countEmbeddingFor(legacyId)).isEqualTo(1);
    }

    // =========================================================================
    // TC-SEC-17 — auditoria
    // =========================================================================

    @Test @Order(17)
    @DisplayName("TC-SEC-17: auditoria regista organizationId/userId/detalhes de transicao; operacoes cruzadas negadas nao deixam rasto no alvo")
    void auditIntegrity() {
        // Eventos do QA da Org A pertencem todos a Org A com utilizador preenchido
        var eventsA = auditRepository
                .findByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
                        ORG_A, "KnowledgeQuestionAnswer", qaA);
        assertThat(eventsA).isNotEmpty();
        eventsA.forEach(e -> {
            assertThat(e.getOrganizationId()).isEqualTo(ORG_A);
            assertThat(e.getUserId()).isEqualTo(ADMIN_A);
            assertThat(e.getOccurredAt()).isNotNull();
        });

        // Transicoes registam estado anterior e novo (divida da Etapa 6 saldada)
        assertThat(eventsA).anyMatch(e -> e.getMetadata() != null
                && e.getMetadata().contains("previousStatus=")
                && e.getMetadata().contains("newStatus="));

        // As tentativas cruzadas dos testes anteriores nao geraram auditoria
        // atribuida a Org A sobre o QA da Org B
        var crossEvents = auditRepository
                .findByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
                        ORG_A, "KnowledgeQuestionAnswer", qaB);
        assertThat(crossEvents).isEmpty();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void assertNotFoundForCrossOrg(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .as("acesso cruzado deve responder NOT_FOUND, nunca FORBIDDEN")
                        .isEqualTo(ApiErrorCode.NOT_FOUND));
    }

    /** Importa, cura, valida e publica um QA ficticio; devolve o id. */
    private UUID createPublishedQa(UUID orgId, UUID adminId, String extKey,
                                   String question, String answer) {
        try {
            String csv = "externalKey,question,answer,topic,riskLevel\n"
                    + "%s,%s,%s,IVA,LOW\n".formatted(extKey, question, answer);
            importService.importCsv(orgId, adminId, SRC, stream(csv), false, 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        UUID id = qaRepository
                .findByOrganizationIdAndSourceSystemAndExternalKey(orgId, SRC, extKey)
                .orElseThrow()
                .getId();
        curationService.markPendingReview(orgId, adminId, id);
        curationService.updateCuration(orgId, adminId, id, new KnowledgeQaCurationRequest(
                null, answer, answer, KnowledgeTopic.IVA, null, "PT",
                KnowledgeRiskLevel.LOW, false, null, null, null));
        curationService.addSource(orgId, adminId, id, new SourceReferenceRequest(
                KnowledgeSourceType.INTERNAL_OPINION, "Documento ficticio " + extKey,
                null, null, null, null, null, null, null));
        curationService.validate(orgId, adminId, "Revisor Sec", id);
        publicationService.publish(orgId, adminId, "Editor Sec", id);
        return id;
    }

    private InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private long countQaEmbeddings(UUID orgId) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM knowledge_qa_embeddings kqae
                JOIN knowledge_question_answers kqa ON kqa.id = kqae.knowledge_qa_id
                WHERE kqa.organization_id = ?::uuid
                """, Long.class, orgId.toString());
        return n != null ? n : 0L;
    }

    private long countEmbeddingFor(UUID qaId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                Long.class, qaId.toString());
        return n != null ? n : 0L;
    }
}
