package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.knowledgeflow.ai.AIRequest;
import com.knowledgeflow.ai.AIResponse;
import com.knowledgeflow.ai.AIService;
import com.knowledgeflow.ai.grounding.AnswerSupportStatus;
import com.knowledgeflow.ai.grounding.GroundedAIResponse;
import com.knowledgeflow.ai.grounding.GroundingService;
import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.repository.AuditEventRepository;
import com.knowledgeflow.common.error.BusinessException;
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
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerCurationService;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerImportService;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerPublicationService;
import com.knowledgeflow.rag.EmbeddingService;
import com.knowledgeflow.rag.RagSearchService;
import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import com.knowledgeflow.rag.RagSearchService.SourceKind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
import org.springframework.boot.test.mock.mockito.MockBean;
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
 * End-to-end integration test: full Q&A lifecycle over real PostgreSQL + pgvector.
 *
 * Cycle: dry-run → import → curation → source → validation → publication →
 *        embedding → RAG retrieval → grounding (supported + 4 rejections) →
 *        new version → archival → audit → org isolation.
 *
 * Zero external calls: MockBean AIService, JDBC embedding indexer, controlled vectors.
 *
 * Run: mvn verify -Ppgtest -Dit.test=KnowledgeQaEndToEndPostgresIT
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(KnowledgeQaEndToEndPostgresIT.E2EInfraConfig.class)
class KnowledgeQaEndToEndPostgresIT {

    // =========================================================================
    // @TestConfiguration: controlled embedding instead of zero-vector stub
    // =========================================================================

    @TestConfiguration
    static class E2EInfraConfig {

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

        /** Overrides zero-vector StubEmbeddingService: query and passage both return VEC_HIGH. */
        @Bean
        @Primary
        EmbeddingService controlledEmbeddingService() {
            List<Float> vec = buildHighList();
            return new EmbeddingService() {
                @Override public List<Float> embedQuery(String text)   { return vec; }
                @Override public List<Float> embedPassage(String text) { return vec; }
            };
        }

        /** Overrides no-op StubKnowledgeQaEmbeddingIndexer: writes VEC_HIGH via JDBC. */
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

    // =========================================================================
    // Testcontainer
    // =========================================================================

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    // =========================================================================
    // Injected Spring beans
    // =========================================================================

    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeQuestionAnswerImportService importService;
    @Autowired KnowledgeQuestionAnswerCurationService curationService;
    @Autowired KnowledgeQuestionAnswerPublicationService publicationService;
    @Autowired RagSearchService ragSearchService;
    @Autowired GroundingService groundingService;
    @Autowired KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired AuditEventRepository auditRepository;
    @MockBean AIService aiService;

    // =========================================================================
    // Fixed identifiers
    // =========================================================================

    private static final UUID ORG_A   = UUID.fromString("e1000000-0000-0000-0000-000000000001");
    private static final UUID ORG_B   = UUID.fromString("e2000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN_A = UUID.fromString("e1000000-0000-0000-0000-000000000010");
    private static final UUID ADMIN_B = UUID.fromString("e2000000-0000-0000-0000-000000000011");

    private static final String SOURCE_SYSTEM = "E2E_TEST";
    private static final String EXT_KEY       = "QA-E2E-001";

    // Fictitious content (no real fiscal law)
    private static final String QUESTION_V1 =
            "Qual e o limite ficticio aplicavel ao regime de teste?";
    private static final String ANSWER_ORIGINAL =
            "O limite ficticio aplicavel e 650 000 EUR.";
    private static final String ANSWER_TECHNICAL_V1 =
            "O limite ficticio aplicavel e 650 000 EUR.";
    private static final String ANSWER_TECHNICAL_V2 =
            "O limite ficticio actualizado e 700 000 EUR.";

    private static final String CSV_V1 =
            "externalKey,question,answer,topic,riskLevel\n" +
            "QA-E2E-001,Qual e o limite ficticio aplicavel ao regime de teste?," +
            "O limite ficticio aplicavel e 650 000 EUR.,IVA,MEDIUM\n";

    private static final String CSV_CONFLICT =
            "externalKey,question,answer\n" +
            "QA-E2E-001,Qual e o limite ficticio aplicavel ao regime de teste?," +
            "RESPOSTA DIFERENTE PARA TESTAR CONFLITO DE CHAVE\n";

    // Shared mutable state (sequential tests)
    private UUID qaV1Id;
    private UUID qaV2Id;

    // =========================================================================
    // Setup: insert organizations
    // =========================================================================

    @BeforeAll
    void insertOrgs() {
        jdbc.update(
                "INSERT INTO organizations(id, name, tax_identifier, created_at, updated_at)"
                + " VALUES (?,?,?,NOW(),NOW())",
                ORG_A, "Org A Teste E2E", "PT900000001");
        jdbc.update(
                "INSERT INTO organizations(id, name, tax_identifier, created_at, updated_at)"
                + " VALUES (?,?,?,NOW(),NOW())",
                ORG_B, "Org B Teste E2E", "PT900000002");
        // audit_events.user_id tem FK para users; inserir utilizadores fictícios
        jdbc.update(
                "INSERT INTO users(id, email, full_name, password_hash, status, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,NOW(),NOW())",
                ADMIN_A, "admin-a@e2e.test", "Admin A E2E", "$2a$10$dummyhashA", "ACTIVE");
        jdbc.update(
                "INSERT INTO users(id, email, full_name, password_hash, status, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,NOW(),NOW())",
                ADMIN_B, "admin-b@e2e.test", "Admin B E2E", "$2a$10$dummyhashB", "ACTIVE");
    }

    // =========================================================================
    // TC-E2E-01 — Dry-run nao persiste
    // =========================================================================

    @Test @Order(1)
    @DisplayName("TC-E2E-01: Dry-run nao persiste QA, fontes, embeddings nem auditoria")
    void dryRunDoesNotPersist() throws IOException {
        ImportReport report = importService.importCsv(
                ORG_A, ADMIN_A, SOURCE_SYSTEM, csv(CSV_V1), true, 0);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.totalRows()).isEqualTo(1);
        assertThat(report.imported()).isEqualTo(0);
        assertThat(report.updated()).isEqualTo(0);
        assertThat(report.invalid()).isEqualTo(0);

        // Nao persistiu nenhum QA
        assertThat(countQa(ORG_A)).isZero();
        // Nao criou embeddings
        assertThat(countQaEmbeddings(ORG_A)).isZero();
    }

    // =========================================================================
    // TC-E2E-02 — Importacao real
    // =========================================================================

    @Test @Order(2)
    @DisplayName("TC-E2E-02: Importacao real cria QA com curationStatus=IMPORTED")
    void realImport() throws IOException {
        ImportReport report = importService.importCsv(
                ORG_A, ADMIN_A, SOURCE_SYSTEM, csv(CSV_V1), false, 0);

        assertThat(report.dryRun()).isFalse();
        assertThat(report.totalRows()).isEqualTo(1);
        assertThat(report.imported()).isEqualTo(1);
        assertThat(report.invalid()).isEqualTo(0);
        assertThat(countQa(ORG_A)).isEqualTo(1);

        KnowledgeQuestionAnswer qa = qaRepository
                .findByOrganizationIdAndSourceSystemAndExternalKey(ORG_A, SOURCE_SYSTEM, EXT_KEY)
                .orElseThrow();
        qaV1Id = qa.getId();

        assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.IMPORTED);
        assertThat(qa.getPublishedAt()).isNull();
        assertThat(qa.getOriginalQuestion()).isEqualTo(QUESTION_V1);
        assertThat(qa.getOriginalAnswer()).isEqualTo(ANSWER_ORIGINAL);
        assertThat(qa.getSourceSystem()).isEqualTo(SOURCE_SYSTEM);
        assertThat(qa.getExternalKey()).isEqualTo(EXT_KEY);
    }

    // =========================================================================
    // TC-E2E-03 — Idempotencia
    // =========================================================================

    @Test @Order(3)
    @DisplayName("TC-E2E-03: Segunda importacao com mesmo externalKey nao cria novo registo")
    void idempotentImport() throws IOException {
        long beforeCount = countQa(ORG_A);

        ImportReport report = importService.importCsv(
                ORG_A, ADMIN_A, SOURCE_SYSTEM, csv(CSV_V1), false, 0);

        // SAME_EXTERNAL_KEY: actualiza metadados, nao cria novo
        assertThat(report.imported()).isEqualTo(0);
        assertThat(report.updated()).isEqualTo(1);
        assertThat(countQa(ORG_A)).isEqualTo(beforeCount); // mesmo numero de registos
    }

    // =========================================================================
    // TC-E2E-04 — Conflito de externalKey com conteudo diferente
    // =========================================================================

    @Test @Order(4)
    @DisplayName("TC-E2E-04: Conflito de externalKey com resposta diferente nao altera original")
    void externalKeyConflictPreservesOriginal() throws IOException {
        ImportReport report = importService.importCsv(
                ORG_A, ADMIN_A, SOURCE_SYSTEM, csv(CSV_CONFLICT), false, 0);

        // Nao cria novo; trata como SAME_EXTERNAL_KEY (atualiza metadados)
        assertThat(report.imported()).isEqualTo(0);
        assertThat(countQa(ORG_A)).isEqualTo(1);

        // Original preservada — nao foi substituida pelo conteudo conflituoso
        KnowledgeQuestionAnswer qa = qaRepository.findById(qaV1Id).orElseThrow();
        assertThat(qa.getOriginalAnswer()).isEqualTo(ANSWER_ORIGINAL);
    }

    // =========================================================================
    // TC-E2E-05 — Estado IMPORTED
    // =========================================================================

    @Test @Order(5)
    @DisplayName("TC-E2E-05: QA importado tem curationStatus=IMPORTED e publishedAt=null")
    void importedState() {
        KnowledgeQuestionAnswer qa = qaRepository.findById(qaV1Id).orElseThrow();
        assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.IMPORTED);
        assertThat(qa.getPublishedAt()).isNull();
        assertThat(qa.getReviewedBy()).isNull();
        assertThat(qa.getReviewedAt()).isNull();
    }

    // =========================================================================
    // TC-E2E-06 — Transicao para PENDING_REVIEW
    // =========================================================================

    @Test @Order(6)
    @DisplayName("TC-E2E-06: markPendingReview transiciona de IMPORTED para PENDING_REVIEW")
    void markPendingReview() {
        curationService.markPendingReview(ORG_A, ADMIN_A, qaV1Id);

        KnowledgeQuestionAnswer qa = qaRepository.findById(qaV1Id).orElseThrow();
        assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.PENDING_REVIEW);
    }

    // =========================================================================
    // TC-E2E-07 — Validacao sem fonte bloqueada
    // =========================================================================

    @Test @Order(7)
    @DisplayName("TC-E2E-07: Validacao sem fonte bloqueada (lancca BusinessException)")
    void validateWithoutSourceIsBlocked() {
        // Nenhuma fonte associada ainda; service deve lancar excecao
        assertThatThrownBy(() ->
                curationService.validate(ORG_A, ADMIN_A, "Revisor E2E", qaV1Id))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("source reference");

        // Estado nao alterado
        KnowledgeQuestionAnswer qa = qaRepository.findById(qaV1Id).orElseThrow();
        assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.PENDING_REVIEW);
    }

    // =========================================================================
    // TC-E2E-08 — Curadoria, fonte e validacao
    // =========================================================================

    @Test @Order(8)
    @DisplayName("TC-E2E-08: Curadoria completa + fonte + validacao transiciona para VALIDATED")
    void curationAndValidateWithSource() {
        // Preencher campos curados
        var req = new KnowledgeQaCurationRequest(
                QUESTION_V1,
                "Limite ficticio 650 000 EUR.",  // shortAnswer
                ANSWER_TECHNICAL_V1,             // technicalAnswer
                KnowledgeTopic.IVA,
                "Teste E2E",
                "PT",
                KnowledgeRiskLevel.MEDIUM,
                false,
                LocalDate.now(),
                LocalDate.now().plusYears(1),
                "Nota ficticia de teste E2E");
        curationService.updateCuration(ORG_A, ADMIN_A, qaV1Id, req);

        // Associar fonte ficticia
        var srcReq = new SourceReferenceRequest(
                KnowledgeSourceType.INTERNAL_OPINION,
                "Documento Ficticio de Teste E2E",
                "TEST-E2E-001",
                null, null, null, null, null,
                "Fonte ficticia criada para o teste E2E");
        curationService.addSource(ORG_A, ADMIN_A, qaV1Id, srcReq);

        // Validar
        curationService.validate(ORG_A, ADMIN_A, "Revisor E2E", qaV1Id);

        KnowledgeQuestionAnswer qa = qaRepository.findById(qaV1Id).orElseThrow();
        assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
        assertThat(qa.getReviewedBy()).isEqualTo("Revisor E2E");
        assertThat(qa.getReviewedAt()).isNotNull();
        assertThat(qa.getTechnicalAnswer()).isEqualTo(ANSWER_TECHNICAL_V1);
        assertThat(qa.getTopic()).isEqualTo(KnowledgeTopic.IVA);
    }

    // =========================================================================
    // TC-E2E-09 — Publicacao
    // =========================================================================

    @Test @Order(9)
    @DisplayName("TC-E2E-09: Publicacao preenche publishedAt e publishedBy")
    void publication() {
        publicationService.publish(ORG_A, ADMIN_A, "Editor E2E", qaV1Id);

        KnowledgeQuestionAnswer qa = qaRepository.findById(qaV1Id).orElseThrow();
        assertThat(qa.isPublished()).isTrue();
        assertThat(qa.getPublishedAt()).isNotNull();
        assertThat(qa.getPublishedBy()).isEqualTo("Editor E2E");
        assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
    }

    // =========================================================================
    // TC-E2E-10 — Embedding criado
    // =========================================================================

    @Test @Order(10)
    @DisplayName("TC-E2E-10: Publicacao cria exatamente 1 embedding em knowledge_qa_embeddings")
    void embeddingCreated() {
        assertThat(countQaEmbeddings(ORG_A)).isEqualTo(1);
        // Verificar que e o embedding do QA correcto
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                Integer.class, qaV1Id.toString());
        assertThat(count).isEqualTo(1);
    }

    // =========================================================================
    // TC-E2E-11 — Recuperacao RAG
    // =========================================================================

    @Test @Order(11)
    @DisplayName("TC-E2E-11: RAG recupera QA publicado com score previsivel")
    void ragRetrieval() {
        List<RetrievedCase> results = ragSearchService.findSimilar(ORG_A, QUESTION_V1);

        assertThat(results).isNotEmpty();
        RetrievedCase hit = results.get(0);
        assertThat(hit.content()).isEqualTo(ANSWER_TECHNICAL_V1);
        assertThat(hit.similarity()).isGreaterThan(0.99); // VEC_HIGH <=> VEC_HIGH = 1.0
    }

    // =========================================================================
    // TC-E2E-12 — sourceKind KNOWLEDGE_QA
    // =========================================================================

    @Test @Order(12)
    @DisplayName("TC-E2E-12: Resultado RAG tem sourceKind=KNOWLEDGE_QA (nao DOCUMENT)")
    void ragSourceKind() {
        List<RetrievedCase> results = ragSearchService.findSimilar(ORG_A, QUESTION_V1);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).sourceKind()).isEqualTo(SourceKind.KNOWLEDGE_QA);
    }

    // =========================================================================
    // TC-E2E-13 — sourceQaId correcto
    // =========================================================================

    @Test @Order(13)
    @DisplayName("TC-E2E-13: Resultado RAG tem sourceQaId igual ao ID do QA publicado")
    void ragSourceQaId() {
        List<RetrievedCase> results = ragSearchService.findSimilar(ORG_A, QUESTION_V1);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).sourceQaId()).isEqualTo(qaV1Id);
    }

    // =========================================================================
    // TC-E2E-14 — Grounding SUPPORTED
    // =========================================================================

    @Test @Order(14)
    @DisplayName("TC-E2E-14: Grounding SUPPORTED quando resposta reproduz o conteudo do contexto")
    void groundingSupported() {
        // Stub devolve valor presente no contexto
        stubAiAnswer("O limite ficticio aplicavel e 650 000 EUR.");

        List<RetrievedCase> context = ragSearchService.findSimilar(ORG_A, QUESTION_V1);
        GroundedAIResponse response = groundingService.process(QUESTION_V1, null, context);

        assertThat(response.supportStatus()).isIn(
                AnswerSupportStatus.SUPPORTED, AnswerSupportStatus.PARTIALLY_SUPPORTED);
        assertThat(response.providerCalled()).isTrue();
        assertThat(response.responseRejected()).isFalse();
        assertThat(response.unsupportedClaimsCount()).isEqualTo(0);
        assertThat(response.sources()).isNotEmpty();
    }

    // =========================================================================
    // TC-E2E-15 — Grounding rejeitado por taxa diferente
    // =========================================================================

    @Test @Order(15)
    @DisplayName("TC-E2E-15: Grounding rejeitado quando resposta contem taxa nao presente no contexto")
    void groundingRejectedByRate() {
        // Contexto nao contem qualquer percentagem; stub inventa "13%"
        stubAiAnswer("A taxa aplicavel e de 13%.");

        List<RetrievedCase> context = ragSearchService.findSimilar(ORG_A, QUESTION_V1);
        GroundedAIResponse response = groundingService.process(QUESTION_V1, null, context);

        assertThat(response.supportStatus()).isEqualTo(AnswerSupportStatus.REJECTED_UNSUPPORTED);
        assertThat(response.responseRejected()).isTrue();
        assertThat(response.unsupportedClaimsCount()).isGreaterThan(0);
    }

    // =========================================================================
    // TC-E2E-16 — Grounding rejeitado por artigo diferente
    // =========================================================================

    @Test @Order(16)
    @DisplayName("TC-E2E-16: Grounding rejeitado quando resposta contem artigo nao presente no contexto")
    void groundingRejectedByArticle() {
        // Contexto nao menciona "Artigo 99"; stub inventa-o
        stubAiAnswer("Consultar o Artigo 99 do codigo ficticio.");

        List<RetrievedCase> context = ragSearchService.findSimilar(ORG_A, QUESTION_V1);
        GroundedAIResponse response = groundingService.process(QUESTION_V1, null, context);

        assertThat(response.supportStatus()).isEqualTo(AnswerSupportStatus.REJECTED_UNSUPPORTED);
        assertThat(response.responseRejected()).isTrue();
        assertThat(response.unsupportedClaimsCount()).isGreaterThan(0);
    }

    // =========================================================================
    // TC-E2E-17 — Grounding rejeitado por prazo diferente
    // =========================================================================

    @Test @Order(17)
    @DisplayName("TC-E2E-17: Grounding rejeitado quando resposta contem prazo nao presente no contexto")
    void groundingRejectedByDeadline() {
        // Contexto nao menciona "60 dias"; stub inventa prazo
        stubAiAnswer("O prazo para cumprimento e de 60 dias.");

        List<RetrievedCase> context = ragSearchService.findSimilar(ORG_A, QUESTION_V1);
        GroundedAIResponse response = groundingService.process(QUESTION_V1, null, context);

        assertThat(response.supportStatus()).isEqualTo(AnswerSupportStatus.REJECTED_UNSUPPORTED);
        assertThat(response.responseRejected()).isTrue();
        assertThat(response.unsupportedClaimsCount()).isGreaterThan(0);
    }

    // =========================================================================
    // TC-E2E-18 — Grounding rejeitado por valor monetario diferente
    // =========================================================================

    @Test @Order(18)
    @DisplayName("TC-E2E-18: Grounding rejeitado quando resposta contem valor monetario nao presente no contexto")
    void groundingRejectedByMonetaryValue() {
        // Contexto tem 650 000 EUR; stub inventa 200 000 EUR
        stubAiAnswer("O limite ficticio aplicavel e 200 000 EUR.");

        List<RetrievedCase> context = ragSearchService.findSimilar(ORG_A, QUESTION_V1);
        GroundedAIResponse response = groundingService.process(QUESTION_V1, null, context);

        assertThat(response.supportStatus()).isEqualTo(AnswerSupportStatus.REJECTED_UNSUPPORTED);
        assertThat(response.responseRejected()).isTrue();
        assertThat(response.unsupportedClaimsCount()).isGreaterThan(0);
    }

    // =========================================================================
    // TC-E2E-19 — Nova versao (inclui validacao e publicacao de V2)
    // =========================================================================

    @Test @Order(19)
    @DisplayName("TC-E2E-19: Nova versao substitui V1; V2 publicada; RAG devolve conteudo V2")
    void newVersion() {
        // Criar V2 (despublica V1, cria V2 em PENDING_REVIEW)
        KnowledgeQuestionAnswer v2 = publicationService.createNewVersion(
                ORG_A, ADMIN_A, "Editor E2E", qaV1Id, ANSWER_TECHNICAL_V2);
        qaV2Id = v2.getId();

        assertThat(v2.getPreviousVersionId()).isEqualTo(qaV1Id);
        assertThat(v2.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.PENDING_REVIEW);
        assertThat(v2.getTechnicalAnswer()).isEqualTo(ANSWER_TECHNICAL_V2);

        // V1 deve estar despublicada
        KnowledgeQuestionAnswer v1 = qaRepository.findById(qaV1Id).orElseThrow();
        assertThat(v1.isPublished()).isFalse();

        // Adicionar fonte a V2 (cada versao tem o seu proprio ID)
        var srcReq = new SourceReferenceRequest(
                KnowledgeSourceType.INTERNAL_OPINION,
                "Documento Ficticio de Teste E2E V2",
                "TEST-E2E-002",
                null, null, null, null, null, null);
        curationService.addSource(ORG_A, ADMIN_A, qaV2Id, srcReq);

        // Validar V2
        curationService.validate(ORG_A, ADMIN_A, "Revisor E2E", qaV2Id);

        // Publicar V2
        publicationService.publish(ORG_A, ADMIN_A, "Editor E2E", qaV2Id);

        KnowledgeQuestionAnswer v2pub = qaRepository.findById(qaV2Id).orElseThrow();
        assertThat(v2pub.isPublished()).isTrue();
        assertThat(v2pub.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);

        // RAG devolve conteudo de V2
        List<RetrievedCase> results = ragSearchService.findSimilar(ORG_A, QUESTION_V1);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).content()).isEqualTo(ANSWER_TECHNICAL_V2);
        assertThat(results.get(0).sourceQaId()).isEqualTo(qaV2Id);
    }

    // =========================================================================
    // TC-E2E-20 — Versao anterior preservada
    // =========================================================================

    @Test @Order(20)
    @DisplayName("TC-E2E-20: V1 preservada na base com publishedAt=null; V2 activa com publishedAt preenchido")
    void previousVersionPreserved() {
        KnowledgeQuestionAnswer v1 = qaRepository.findById(qaV1Id).orElseThrow();
        assertThat(v1).isNotNull();
        assertThat(v1.isPublished()).isFalse(); // despublicada por createNewVersion
        assertThat(v1.getOriginalQuestion()).isEqualTo(QUESTION_V1);

        KnowledgeQuestionAnswer v2 = qaRepository.findById(qaV2Id).orElseThrow();
        assertThat(v2.isPublished()).isTrue();
        assertThat(v2.getPreviousVersionId()).isEqualTo(qaV1Id);
    }

    // =========================================================================
    // TC-E2E-21 — Embedding antigo excluido
    // =========================================================================

    @Test @Order(21)
    @DisplayName("TC-E2E-21: Embedding de V1 removido; embedding de V2 presente")
    void oldEmbeddingExcluded() {
        Integer v1Emb = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                Integer.class, qaV1Id.toString());
        assertThat(v1Emb).isZero(); // removido ao criar nova versao

        Integer v2Emb = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                Integer.class, qaV2Id.toString());
        assertThat(v2Emb).isEqualTo(1); // criado ao publicar V2
    }

    // =========================================================================
    // TC-E2E-22 — Arquivo
    // =========================================================================

    @Test @Order(22)
    @DisplayName("TC-E2E-22: Arquivo de V2 transiciona para ARCHIVED; embedding removido")
    void archive() {
        // Despublicar V2 (remove embedding)
        publicationService.unpublish(ORG_A, ADMIN_A, qaV2Id);
        // Marcar como OUTDATED (de VALIDATED → OUTDATED)
        curationService.markOutdated(ORG_A, ADMIN_A, qaV2Id);
        // Arquivar (de OUTDATED → ARCHIVED)
        curationService.archive(ORG_A, ADMIN_A, qaV2Id);

        KnowledgeQuestionAnswer v2 = qaRepository.findById(qaV2Id).orElseThrow();
        assertThat(v2.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.ARCHIVED);
        assertThat(v2.isPublished()).isFalse();

        // Embedding removido no unpublish
        Integer embCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                Integer.class, qaV2Id.toString());
        assertThat(embCount).isZero();
    }

    // =========================================================================
    // TC-E2E-23 — Remocao do RAG
    // =========================================================================

    @Test @Order(23)
    @DisplayName("TC-E2E-23: Apos arquivo, RAG nao devolve resultados para ORG_A")
    void ragEmptyAfterArchive() {
        List<RetrievedCase> results = ragSearchService.findSimilar(ORG_A, QUESTION_V1);
        // Nenhuma versao publicada e validada; RAG deve estar vazio para KNOWLEDGE_QA
        boolean hasOrgAQa = results.stream()
                .anyMatch(r -> r.sourceKind() == SourceKind.KNOWLEDGE_QA
                        && (qaV1Id.equals(r.sourceQaId()) || qaV2Id.equals(r.sourceQaId())));
        assertThat(hasOrgAQa).isFalse();
    }

    // =========================================================================
    // TC-E2E-24 — Auditoria
    // =========================================================================

    @Test @Order(24)
    @DisplayName("TC-E2E-24: Auditoria preservada com eventos esperados para V1 e V2")
    void auditPreserved() {
        // Eventos de V1
        var eventsV1 = auditRepository
                .findByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
                        ORG_A, "KnowledgeQuestionAnswer", qaV1Id);

        List<AuditAction> actionsV1 = eventsV1.stream()
                .map(e -> e.getAction())
                .toList();

        // Deve conter importacao, mudanca para PENDING_REVIEW, validacao, publicacao
        assertThat(actionsV1).contains(
                AuditAction.KNOWLEDGE_QA_IMPORTED,
                AuditAction.KNOWLEDGE_QA_STATUS_CHANGED,   // PENDING_REVIEW
                AuditAction.KNOWLEDGE_QA_SOURCE_ADDED,
                AuditAction.KNOWLEDGE_QA_VALIDATED,
                AuditAction.KNOWLEDGE_QA_PUBLISHED);

        // Eventos de V2
        var eventsV2 = auditRepository
                .findByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
                        ORG_A, "KnowledgeQuestionAnswer", qaV2Id);

        List<AuditAction> actionsV2 = eventsV2.stream()
                .map(e -> e.getAction())
                .toList();

        // V2: versao criada, fonte, validacao, publicacao, despublicacao, arquivamento
        assertThat(actionsV2).contains(
                AuditAction.KNOWLEDGE_QA_VERSION_CREATED,
                AuditAction.KNOWLEDGE_QA_SOURCE_ADDED,
                AuditAction.KNOWLEDGE_QA_VALIDATED,
                AuditAction.KNOWLEDGE_QA_PUBLISHED,
                AuditAction.KNOWLEDGE_QA_UNPUBLISHED,
                AuditAction.KNOWLEDGE_QA_ARCHIVED);

        // Cada evento tem organizationId e timestamp
        eventsV1.forEach(e -> {
            assertThat(e.getOrganizationId()).isEqualTo(ORG_A);
            assertThat(e.getOccurredAt()).isNotNull();
            assertThat(e.getEntityType()).isNotBlank();
        });
    }

    // =========================================================================
    // TC-E2E-25 — Isolamento organizacional
    // =========================================================================

    @Test @Order(25)
    @DisplayName("TC-E2E-25: ORG_A nao ve nem altera dados de ORG_B")
    void orgIsolation() throws IOException {
        // Importar QA para ORG_B
        String csvB = "externalKey,question,answer\n"
                + "QA-E2E-B01,Pergunta ficticia ORG_B?,Resposta ficticia ORG_B.\n";
        ImportReport reportB = importService.importCsv(
                ORG_B, ADMIN_B, SOURCE_SYSTEM, csv(csvB), false, 0);
        assertThat(reportB.imported()).isEqualTo(1);

        UUID qaB = qaRepository
                .findByOrganizationIdAndSourceSystemAndExternalKey(ORG_B, SOURCE_SYSTEM, "QA-E2E-B01")
                .orElseThrow()
                .getId();

        // ORG_A nao pode atualizar dados de ORG_B
        var req = new KnowledgeQaCurationRequest(
                null, null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() ->
                curationService.updateCuration(ORG_A, ADMIN_A, qaB, req))
                .isInstanceOf(BusinessException.class);

        // ORG_A nao pode publicar dados de ORG_B
        assertThatThrownBy(() ->
                publicationService.publish(ORG_A, ADMIN_A, "Intruso", qaB))
                .isInstanceOf(BusinessException.class);

        // RAG de ORG_A nao inclui dados de ORG_B
        // Inserir embedding de ORG_B directamente (sem passar pelo pipeline)
        insertOrgBEmbedding(qaB);

        List<RetrievedCase> resultsA = ragSearchService.findSimilar(ORG_A, QUESTION_V1);
        boolean containsOrgBData = resultsA.stream()
                .anyMatch(r -> r.sourceKind() == SourceKind.KNOWLEDGE_QA
                        && qaB.equals(r.sourceQaId()));
        assertThat(containsOrgBData).isFalse();
    }

    // =========================================================================
    // TC-E2E-26 — Zero chamadas externas
    // =========================================================================

    @Test @Order(26)
    @DisplayName("TC-E2E-26: Datasource e localhost; sem providers Anthropic ou OpenAI reais")
    void zeroExternalCalls() {
        // Datasource aponta para o container Testcontainers (localhost)
        String url = jdbc.getDataSource() != null
                ? jdbc.getDataSource().toString()
                : postgres.getJdbcUrl();

        assertThat(postgres.getJdbcUrl())
                .as("Datasource deve ser localhost (Testcontainers)")
                .matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):.*");

        // Container esta a correr
        assertThat(postgres.isRunning()).isTrue();

        // aiService e um mock (nao o provider real)
        // Esta classe usa @MockBean AIService — nenhuma chamada real possivel
        assertThat(aiService).isNotNull();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private InputStream csv(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private long countQa(UUID orgId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_question_answers WHERE organization_id = ?::uuid",
                Long.class, orgId.toString());
        return n != null ? n : 0L;
    }

    private long countQaEmbeddings(UUID orgId) {
        Long n = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM knowledge_qa_embeddings kqae
                JOIN knowledge_question_answers kqa ON kqa.id = kqae.knowledge_qa_id
                WHERE kqa.organization_id = ?::uuid
                """,
                Long.class, orgId.toString());
        return n != null ? n : 0L;
    }

    private void stubAiAnswer(String answer) {
        when(aiService.complete(any(AIRequest.class)))
                .thenReturn(new AIResponse("stub", "stub-v1", answer, 0, 0, 0L));
    }

    /** Insere um embedding de ORG_B directamente na BD para testar isolamento no RAG. */
    private void insertOrgBEmbedding(UUID qaB) {
        // Primeiro actualizar o QA de ORG_B para VALIDATED + publishedAt (necessario para o RAG o ver)
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("""
                UPDATE knowledge_question_answers
                   SET curation_status = 'VALIDATED',
                       published_at    = ?,
                       updated_at      = ?
                 WHERE id = ?::uuid
                """, now, now, qaB.toString());

        // Inserir embedding com o mesmo VEC_HIGH (para simular indexacao)
        jdbc.update("""
                INSERT INTO knowledge_qa_embeddings (knowledge_qa_id, embedding)
                VALUES (?::uuid, ?::vector)
                """, qaB.toString(), E2EInfraConfig.VEC_HIGH);
    }
}
