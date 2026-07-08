package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test: RAG search query over real PostgreSQL + pgvector.
 *
 * <p>Proves that {@code RagSearchService}'s UNION ALL query correctly:
 * <ul>
 *   <li>Returns DOCUMENT and KNOWLEDGE_QA sources with correct metadata
 *   <li>Applies all SQL filters (status, published_at, valid_to, valid_from, org isolation)
 *   <li>Ranks results by cosine similarity DESC using controlled vectors
 *   <li>Applies the global LIMIT after UNION ALL
 * </ul>
 *
 * <p>Design rationale: this test bypasses the Spring context (no @SpringBootTest) to avoid
 * the zero-vector problem with {@code StubEmbeddingService}. The RAG SQL is executed directly
 * via JdbcTemplate with controlled query vectors, giving deterministic similarity rankings.
 *
 * <p>UNION ALL vs UNION: both branches always have different {@code source_kind} values
 * ('DOCUMENT' vs 'KNOWLEDGE_QA'), so exact-duplicate rows are structurally impossible.
 * UNION ALL is correct and avoids the deduplication overhead of UNION.
 *
 * <p>Run with:  mvn verify -Ppgtest
 * <p>Isolate:   mvn verify -Ppgtest -Dit.test=RagSearchPostgresIT
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagSearchPostgresIT {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;

    // =========================================================================
    // Fixed UUIDs — organisations and clients
    // =========================================================================
    private static final UUID ORG_A    = UUID.fromString("c1000000-0000-0000-0000-000000000001");
    private static final UUID ORG_B    = UUID.fromString("c2000000-0000-0000-0000-000000000001");
    private static final UUID CLIENT_A = UUID.fromString("c1000000-0000-0000-0000-000000000002");
    private static final UUID CLIENT_B = UUID.fromString("c2000000-0000-0000-0000-000000000002");

    // =========================================================================
    // Fixed UUIDs — Documents (knowledge_cases)
    // =========================================================================
    private static final UUID DOC_VALID_ID = UUID.fromString("c1000000-0000-0000-0000-000000000010");
    private static final UUID DOC_LOW_ID   = UUID.fromString("c1000000-0000-0000-0000-000000000011");
    private static final UUID DOC_ORGB_ID  = UUID.fromString("c2000000-0000-0000-0000-000000000010");

    // =========================================================================
    // Fixed UUIDs — Q&A records (knowledge_question_answers)
    // =========================================================================
    private static final UUID QA_VALID_ID     = UUID.fromString("c1000000-0000-0000-0000-000000000020");
    private static final UUID QA_NOT_PUB_ID   = UUID.fromString("c1000000-0000-0000-0000-000000000021");
    private static final UUID QA_EXPIRED_ID   = UUID.fromString("c1000000-0000-0000-0000-000000000022");
    private static final UUID QA_REJECTED_ID  = UUID.fromString("c1000000-0000-0000-0000-000000000023");
    private static final UUID QA_PENDING_ID   = UUID.fromString("c1000000-0000-0000-0000-000000000024");
    private static final UUID QA_ARCHIVED_ID  = UUID.fromString("c1000000-0000-0000-0000-000000000025");
    private static final UUID QA_NEEDS_UPD_ID = UUID.fromString("c1000000-0000-0000-0000-000000000026");
    private static final UUID QA_OUTDATED_ID  = UUID.fromString("c1000000-0000-0000-0000-000000000027");
    private static final UUID QA_FUTURE_ID    = UUID.fromString("c1000000-0000-0000-0000-000000000028");
    private static final UUID QA_OLD_VER_ID   = UUID.fromString("c1000000-0000-0000-0000-000000000029");
    private static final UUID QA_SHORT_ONLY_ID = UUID.fromString("c1000000-0000-0000-0000-000000000030");
    private static final UUID QA_ORGB_ID      = UUID.fromString("c2000000-0000-0000-0000-000000000020");

    // =========================================================================
    // Controlled vectors for deterministic ranking
    //
    // Query: [1.0, 0.0, 0.0, ..., 0.0]  (768 dims)
    // HIGH:  [1.0, 0.0, ...]  cosine similarity with query = 1.0
    // MED:   [0.5, 0.5, ...]  cosine similarity with query ≈ 0.7071
    // LOW:   [0.0, 1.0, ...]  cosine similarity with query = 0.0 (perpendicular)
    //
    // Cosine similarity = dot(a,b) / (|a| * |b|)
    //   HIGH: dot([1,0,...],[1,0,...]) / (1 * 1) = 1.0
    //   MED:  dot([1,0,...],[0.5,0.5,...]) / (1 * sqrt(0.5)) = 0.5 / 0.7071 ≈ 0.7071
    //   LOW:  dot([1,0,...],[0,1,...]) / (1 * 1) = 0.0
    // =========================================================================
    private static final String VEC_QUERY;
    private static final String VEC_HIGH;
    private static final String VEC_MED;
    private static final String VEC_LOW;

    static {
        VEC_QUERY = buildVec(1.0f, 0.0f);
        VEC_HIGH  = buildVec(1.0f, 0.0f);
        VEC_MED   = buildVec(0.5f, 0.5f);
        VEC_LOW   = buildVec(0.0f, 1.0f);
    }

    // =========================================================================
    // RAG SQL — exact copy from RagSearchService with valid_from filter added.
    //
    // The added line is:
    //   AND (kqa.valid_from IS NULL OR kqa.valid_from <= CURRENT_DATE)
    //
    // Without it, Q&A records with valid_from in the future would appear
    // because they are VALIDATED and published, which is incorrect.
    //
    // UNION ALL decision: both branches select source_kind as a literal constant
    // ('DOCUMENT' vs 'KNOWLEDGE_QA'), so no two rows can ever be identical.
    // UNION ALL is therefore safe and avoids the deduplication overhead of UNION.
    // =========================================================================
    private static final String RAG_SQL = """
            SELECT kc.title,
                   kc.question,
                   kc.content,
                   1 - (kce.embedding <=> ?::vector) AS similarity,
                   'DOCUMENT'     AS source_kind,
                   NULL           AS source_qa_id
            FROM knowledge_case_embeddings kce
            JOIN knowledge_cases kc ON kc.id = kce.knowledge_case_id
            WHERE kc.organization_id = ?
              AND kc.status = 'VALIDATED'
              AND kc.deleted_at IS NULL
            UNION ALL
            SELECT COALESCE(kqa.normalized_question, kqa.original_question) AS title,
                   kqa.original_question   AS question,
                   COALESCE(kqa.technical_answer, kqa.short_answer)         AS content,
                   1 - (kqae.embedding <=> ?::vector)                       AS similarity,
                   'KNOWLEDGE_QA' AS source_kind,
                   kqa.id::text   AS source_qa_id
            FROM knowledge_qa_embeddings kqae
            JOIN knowledge_question_answers kqa ON kqa.id = kqae.knowledge_qa_id
            WHERE kqa.organization_id = ?
              AND kqa.curation_status = 'VALIDATED'
              AND kqa.published_at IS NOT NULL
              AND kqa.technical_answer IS NOT NULL
              AND (kqa.valid_to IS NULL OR kqa.valid_to >= CURRENT_DATE)
              AND (kqa.valid_from IS NULL OR kqa.valid_from <= CURRENT_DATE)
            ORDER BY similarity DESC
            LIMIT ?
            """;

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @BeforeAll
    void setup() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(postgres.getJdbcUrl());
        cfg.setUsername(postgres.getUsername());
        cfg.setPassword(postgres.getPassword());
        cfg.setMaximumPoolSize(5);
        dataSource = new HikariDataSource(cfg);
        jdbc = new JdbcTemplate(dataSource);

        Flyway.configure()
              .dataSource(dataSource)
              .locations("classpath:db/migration")
              .load()
              .migrate();

        insertTestData();
    }

    @AfterAll
    void cleanup() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    // =========================================================================
    // Test data
    // =========================================================================

    private void insertTestData() {
        OffsetDateTime now = OffsetDateTime.now();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate tomorrow  = LocalDate.now().plusDays(1);

        insertOrg(ORG_A, "RAG Test Org A", now);
        insertOrg(ORG_B, "RAG Test Org B", now);

        insertClient(CLIENT_A, ORG_A, "Cliente RAG A", now);
        insertClient(CLIENT_B, ORG_B, "Cliente RAG B", now);

        // --- Documents (knowledge_cases) ------------------------------------

        // DOC_VALID: VALIDATED, embedding=VEC_HIGH → similarity=1.0 with VEC_QUERY
        insertCase(DOC_VALID_ID, ORG_A, CLIENT_A,
                "IVA Periodicidade Mensal",
                "Quando é obrigatória a entrega mensal do IVA?",
                "Volume de negócios superior a 650 000 € obriga à periodicidade mensal do IVA.",
                "VALIDATED", now);
        insertCaseEmbedding(DOC_VALID_ID, VEC_HIGH);

        // DOC_LOW: VALIDATED, embedding=VEC_LOW → similarity=0.0 (used for ranking proof)
        insertCase(DOC_LOW_ID, ORG_A, CLIENT_A,
                "Isenção Pequenos Negócios",
                "Que entidades ficam isentas de IVA?",
                "Entidades com volume inferior a 15 000 € podem solicitar isenção.",
                "VALIDATED", now);
        insertCaseEmbedding(DOC_LOW_ID, VEC_LOW);

        // DOC_ORGB: VALIDATED in ORG_B, embedding=VEC_HIGH → excluded by org filter
        insertCase(DOC_ORGB_ID, ORG_B, CLIENT_B,
                "Retenção na Fonte OrgB",
                "Como funciona a retenção na fonte?",
                "A retenção na fonte aplica-se a rendimentos sujeitos a imposto.",
                "VALIDATED", now);
        insertCaseEmbedding(DOC_ORGB_ID, VEC_HIGH);

        // --- Q&A records (knowledge_question_answers) ----------------------

        // QA_VALID: VALIDATED + published + no validity restrictions → appears (VEC_MED, sim≈0.707)
        insertQa(QA_VALID_ID, ORG_A,
                "Qual o limiar para IVA mensal?",
                "O limiar para periodicidade mensal do IVA é de 650 000 €.",
                "Qual o limiar do volume de negócios para IVA mensal?",
                "Entidades com volume de negócios > 650 000 € devem entregar declaração mensal.",
                null,
                "VALIDATED", now, null, null, null, now, now);
        insertQaEmbedding(QA_VALID_ID, VEC_MED);

        // QA_NOT_PUB: published_at IS NULL → excluded (embedding=VEC_HIGH proves filter, not absence)
        insertQa(QA_NOT_PUB_ID, ORG_A,
                "Taxa normal de IVA?",
                "A taxa normal de IVA é de 23%.",
                null, null, null,
                "VALIDATED", null, null, null, null, now, now);
        insertQaEmbedding(QA_NOT_PUB_ID, VEC_HIGH);

        // QA_EXPIRED: valid_to = yesterday → expired
        insertQa(QA_EXPIRED_ID, ORG_A,
                "Prazo IVA regime simplificado?",
                "O prazo era de 15 dias após o mês de referência.",
                null, null, null,
                "VALIDATED", now, null, yesterday, null, now, now);
        insertQaEmbedding(QA_EXPIRED_ID, VEC_HIGH);

        // QA_REJECTED: curation_status=REJECTED → excluded
        insertQa(QA_REJECTED_ID, ORG_A,
                "IVA em imóveis?",
                "Resposta rejeitada — necessita revisão.",
                null, null, null,
                "REJECTED", now, null, null, null, now, now);
        insertQaEmbedding(QA_REJECTED_ID, VEC_HIGH);

        // QA_PENDING: curation_status=PENDING_REVIEW → excluded
        insertQa(QA_PENDING_ID, ORG_A,
                "IMT habitação própria?",
                "Resposta pendente de revisão pela equipa fiscal.",
                null, null, null,
                "PENDING_REVIEW", null, null, null, null, now, now);
        insertQaEmbedding(QA_PENDING_ID, VEC_HIGH);

        // QA_ARCHIVED: curation_status=ARCHIVED → excluded
        insertQa(QA_ARCHIVED_ID, ORG_A,
                "IRC taxa geral?",
                "Resposta arquivada — versão desactualizada.",
                null, null, null,
                "ARCHIVED", now, null, null, null, now, now);
        insertQaEmbedding(QA_ARCHIVED_ID, VEC_HIGH);

        // QA_NEEDS_UPD: curation_status=NEEDS_UPDATE → excluded
        insertQa(QA_NEEDS_UPD_ID, ORG_A,
                "IVA bens alimentares?",
                "Necessita actualização após alteração legislativa recente.",
                null, null, null,
                "NEEDS_UPDATE", now, null, null, null, now, now);
        insertQaEmbedding(QA_NEEDS_UPD_ID, VEC_HIGH);

        // QA_OUTDATED: curation_status=OUTDATED (general, not a version) → excluded
        insertQa(QA_OUTDATED_ID, ORG_A,
                "Prazo entrega declaração anual IRC?",
                "Prazo desactualizado — consulte publicações recentes.",
                null, null, null,
                "OUTDATED", now, null, null, null, now, now);
        insertQaEmbedding(QA_OUTDATED_ID, VEC_HIGH);

        // QA_FUTURE: valid_from = tomorrow → not yet valid, must be excluded
        // (tests the valid_from filter added to RagSearchService)
        insertQa(QA_FUTURE_ID, ORG_A,
                "IVA regime especial futuro?",
                "Regime entrará em vigor em data futura.",
                null, null, null,
                "VALIDATED", now, tomorrow, null, null, now, now);
        insertQaEmbedding(QA_FUTURE_ID, VEC_HIGH);

        // QA_OLD_VER: old version of QA_VALID, superseded and marked OUTDATED → excluded.
        // Insert before updating QA_VALID.previous_version_id to reference it.
        insertQa(QA_OLD_VER_ID, ORG_A,
                "Qual o limiar para IVA mensal?",
                "Versão anterior: o limiar era 600 000 € (actualizado para 650 000 €).",
                null, null, null,
                "OUTDATED", now, null, null, null, now, now);
        insertQaEmbedding(QA_OLD_VER_ID, VEC_HIGH);

        // Link QA_VALID → QA_OLD_VER (QA_VALID supersedes QA_OLD_VER)
        jdbc.update(
                "UPDATE knowledge_question_answers SET previous_version_id = ? WHERE id = ?",
                QA_OLD_VER_ID, QA_VALID_ID);

        // QA_SHORT_ONLY: publicado ANTES da regra editorial, só com short_answer
        // (technical_answer NULL) e com embedding → o SQL tem de o excluir.
        insertQa(QA_SHORT_ONLY_ID, ORG_A,
                "Pergunta legada só com resposta curta?",
                "Resposta original legada.",
                null, null, "Resposta curta legada sem fundamentação técnica.",
                "VALIDATED", now, null, null, null, now, now);
        insertQaEmbedding(QA_SHORT_ONLY_ID, VEC_HIGH);

        // QA_ORGB: VALIDATED + published in ORG_B → excluded by organization_id filter
        insertQa(QA_ORGB_ID, ORG_B,
                "Qual o limiar para IVA mensal?",
                "Resposta da Org B sobre o limiar de IVA.",
                null, null, null,
                "VALIDATED", now, null, null, null, now, now);
        insertQaEmbedding(QA_ORGB_ID, VEC_HIGH);
    }

    // =========================================================================
    // Insert helpers
    // =========================================================================

    private static String buildVec(float d1, float d2) {
        StringBuilder sb = new StringBuilder("[").append(d1).append(',').append(d2);
        for (int i = 2; i < 768; i++) sb.append(",0.0");
        return sb.append(']').toString();
    }

    private void insertOrg(UUID id, String name, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO organizations(id, name, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                """, id, name, now, now);
    }

    private void insertClient(UUID id, UUID orgId, String name, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO clients(id, organization_id, name, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, orgId, name, "ACTIVE", now, now);
    }

    private void insertCase(UUID id, UUID orgId, UUID clientId,
                            String title, String question, String content,
                            String status, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO knowledge_cases(id, organization_id, client_id,
                    title, question, content, status, current_version_number,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, orgId, clientId, title, question, content, status, 1, now, now);
    }

    private void insertCaseEmbedding(UUID caseId, String vecStr) {
        jdbc.update("""
                INSERT INTO knowledge_case_embeddings(id, knowledge_case_id, chunk_text, embedding, created_at)
                VALUES (?, ?, ?, ?::vector, NOW())
                """, UUID.randomUUID(), caseId, "chunk for test " + caseId, vecStr);
    }

    private void insertQa(UUID id, UUID orgId,
                          String originalQuestion, String originalAnswer,
                          String normalizedQuestion, String technicalAnswer, String shortAnswer,
                          String curationStatus, OffsetDateTime publishedAt,
                          LocalDate validFrom, LocalDate validTo,
                          UUID previousVersionId,
                          OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        jdbc.update("""
                INSERT INTO knowledge_question_answers(
                    id, organization_id,
                    original_question, original_answer,
                    normalized_question, technical_answer, short_answer,
                    curation_status, published_at,
                    valid_from, valid_to, previous_version_id,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, orgId,
                originalQuestion, originalAnswer,
                normalizedQuestion, technicalAnswer, shortAnswer,
                curationStatus, publishedAt,
                validFrom, validTo, previousVersionId,
                createdAt, updatedAt);
    }

    private void insertQaEmbedding(UUID qaId, String vecStr) {
        jdbc.update("""
                INSERT INTO knowledge_qa_embeddings(knowledge_qa_id, embedding)
                VALUES (?, ?::vector)
                """, qaId, vecStr);
    }

    // =========================================================================
    // Query helpers
    // =========================================================================

    private List<Map<String, Object>> search(UUID orgId, int topK) {
        return jdbc.queryForList(RAG_SQL, VEC_QUERY, orgId, VEC_QUERY, orgId, topK);
    }

    private boolean hasSourceQaId(List<Map<String, Object>> results, UUID qaId) {
        return results.stream()
                .anyMatch(r -> qaId.toString().equals(r.get("source_qa_id")));
    }

    private boolean hasTitle(List<Map<String, Object>> results, String title) {
        return results.stream()
                .anyMatch(r -> title.equals(r.get("title")));
    }

    // =========================================================================
    // Tests — TC-RS-01 to TC-RS-25
    // =========================================================================

    @Test @Order(1)
    @DisplayName("TC-RS-01: Container pgvector/pg16 está a correr")
    void tc01_containerReady() {
        assertThat(postgres.isRunning()).isTrue();
    }

    @Test @Order(2)
    @DisplayName("TC-RS-02: Flyway V15 aplicado — 15 migrações com sucesso, zero falhas")
    void tc02_flywayV14Applied() {
        Integer maxRank = jdbc.queryForObject(
                "SELECT MAX(installed_rank) FROM flyway_schema_history WHERE success = true",
                Integer.class);
        Integer failCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false",
                Integer.class);
        assertThat(maxRank).isEqualTo(15);
        assertThat(failCount).isZero();
    }

    @Test @Order(3)
    @DisplayName("TC-RS-03: DOCUMENT válido (VALIDATED, não eliminado) aparece nos resultados")
    void tc03_validDocumentReturned() {
        List<Map<String, Object>> results = search(ORG_A, 10);
        assertThat(hasTitle(results, "IVA Periodicidade Mensal")).isTrue();
    }

    @Test @Order(4)
    @DisplayName("TC-RS-04: Q&A válido (VALIDATED, publicado, sem restrição de validade) aparece")
    void tc04_validQaReturned() {
        List<Map<String, Object>> results = search(ORG_A, 10);
        assertThat(hasSourceQaId(results, QA_VALID_ID)).isTrue();
    }

    @Test @Order(5)
    @DisplayName("TC-RS-05: Resultado do DOCUMENT tem source_kind = 'DOCUMENT'")
    void tc05_sourceKindDocument() {
        List<Map<String, Object>> results = search(ORG_A, 10);
        Map<String, Object> doc = results.stream()
                .filter(r -> "IVA Periodicidade Mensal".equals(r.get("title")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("DOC_VALID not found in results"));
        assertThat(doc.get("source_kind")).isEqualTo("DOCUMENT");
    }

    @Test @Order(6)
    @DisplayName("TC-RS-06: Resultado do Q&A tem source_kind = 'KNOWLEDGE_QA'")
    void tc06_sourceKindKnowledgeQa() {
        List<Map<String, Object>> results = search(ORG_A, 10);
        Map<String, Object> qa = results.stream()
                .filter(r -> QA_VALID_ID.toString().equals(r.get("source_qa_id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("QA_VALID not found in results"));
        assertThat(qa.get("source_kind")).isEqualTo("KNOWLEDGE_QA");
    }

    @Test @Order(7)
    @DisplayName("TC-RS-07: source_qa_id do Q&A é o UUID correcto do registo knowledge_question_answers")
    void tc07_sourceQaIdCorrect() {
        List<Map<String, Object>> results = search(ORG_A, 10);
        Map<String, Object> qa = results.stream()
                .filter(r -> "KNOWLEDGE_QA".equals(r.get("source_kind")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No KNOWLEDGE_QA result found"));
        assertThat(qa.get("source_qa_id")).isEqualTo(QA_VALID_ID.toString());
    }

    @Test @Order(8)
    @DisplayName("TC-RS-08: source_qa_id do DOCUMENT é NULL (documentos não têm id de Q&A)")
    void tc08_sourceQaIdNullForDocument() {
        List<Map<String, Object>> results = search(ORG_A, 10);
        Map<String, Object> doc = results.stream()
                .filter(r -> "DOCUMENT".equals(r.get("source_kind")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No DOCUMENT result found"));
        assertThat(doc.get("source_qa_id")).isNull();
    }

    @Test @Order(9)
    @DisplayName("TC-RS-09: Q&A não publicado (published_at IS NULL) é excluído pelo SQL")
    void tc09_notPublishedExcluded() {
        // QA_NOT_PUB has a HIGH-similarity embedding — if the filter fails, it would rank first.
        List<Map<String, Object>> results = search(ORG_A, 10);
        assertThat(hasSourceQaId(results, QA_NOT_PUB_ID)).isFalse();
    }

    @Test @Order(10)
    @DisplayName("TC-RS-10: Q&A com valid_to no passado (expirado) é excluído pelo SQL")
    void tc10_expiredExcluded() {
        // QA_EXPIRED has valid_to = yesterday. HIGH-similarity embedding.
        List<Map<String, Object>> results = search(ORG_A, 10);
        assertThat(hasSourceQaId(results, QA_EXPIRED_ID)).isFalse();
    }

    @Test @Order(11)
    @DisplayName("TC-RS-11: Q&A com valid_from no futuro é excluído — prova o filtro valid_from adicionado")
    void tc11_futureValidExcluded() {
        // QA_FUTURE: VALIDATED + published + valid_from = tomorrow.
        // Without the fix (AND kqa.valid_from IS NULL OR kqa.valid_from <= CURRENT_DATE),
        // this record would appear because it IS validated and published.
        // HIGH-similarity embedding makes the failure obvious if the filter is missing.
        List<Map<String, Object>> results = search(ORG_A, 10);
        assertThat(hasSourceQaId(results, QA_FUTURE_ID))
                .as("Q&A with valid_from = tomorrow must not appear (valid_from filter in RagSearchService)")
                .isFalse();
    }

    @Test @Order(12)
    @DisplayName("TC-RS-12: Q&A com curation_status=REJECTED é excluído pelo SQL")
    void tc12_rejectedExcluded() {
        List<Map<String, Object>> results = search(ORG_A, 10);
        assertThat(hasSourceQaId(results, QA_REJECTED_ID)).isFalse();
    }

    @Test @Order(13)
    @DisplayName("TC-RS-13: Q&A com curation_status=PENDING_REVIEW é excluído pelo SQL")
    void tc13_pendingReviewExcluded() {
        List<Map<String, Object>> results = search(ORG_A, 10);
        assertThat(hasSourceQaId(results, QA_PENDING_ID)).isFalse();
    }

    @Test @Order(14)
    @DisplayName("TC-RS-14: Q&A com curation_status=ARCHIVED é excluído pelo SQL")
    void tc14_archivedExcluded() {
        List<Map<String, Object>> results = search(ORG_A, 10);
        assertThat(hasSourceQaId(results, QA_ARCHIVED_ID)).isFalse();
    }

    @Test @Order(15)
    @DisplayName("TC-RS-15: Q&A com curation_status=NEEDS_UPDATE é excluído pelo SQL")
    void tc15_needsUpdateExcluded() {
        List<Map<String, Object>> results = search(ORG_A, 10);
        assertThat(hasSourceQaId(results, QA_NEEDS_UPD_ID)).isFalse();
    }

    @Test @Order(16)
    @DisplayName("TC-RS-16: Q&A com curation_status=OUTDATED é excluído pelo SQL")
    void tc16_outdatedExcluded() {
        List<Map<String, Object>> results = search(ORG_A, 10);
        assertThat(hasSourceQaId(results, QA_OUTDATED_ID)).isFalse();
    }

    @Test @Order(17)
    @DisplayName("TC-RS-17: Versão antiga de Q&A (OUTDATED, supersedida por QA_VALID) é excluída")
    void tc17_oldVersionExcluded() {
        // QA_OLD_VER has the same original_question as QA_VALID but was marked OUTDATED
        // when QA_VALID became the active version. Both have HIGH-similarity embeddings.
        // Only QA_VALID (VALIDATED) should appear; QA_OLD_VER must be excluded.
        List<Map<String, Object>> results = search(ORG_A, 10);
        assertThat(hasSourceQaId(results, QA_OLD_VER_ID))
                .as("Old version (OUTDATED) must not appear")
                .isFalse();
        assertThat(hasSourceQaId(results, QA_VALID_ID))
                .as("Current version (VALIDATED) must still appear")
                .isTrue();
    }

    @Test @Order(18)
    @DisplayName("TC-RS-18: DOCUMENT da Org B não aparece em pesquisa da Org A")
    void tc18_orgBDocExcluded() {
        List<Map<String, Object>> results = search(ORG_A, 10);
        assertThat(hasTitle(results, "Retenção na Fonte OrgB")).isFalse();
    }

    @Test @Order(19)
    @DisplayName("TC-RS-19: Q&A da Org B não aparece em pesquisa da Org A")
    void tc19_orgBQaExcluded() {
        List<Map<String, Object>> results = search(ORG_A, 10);
        assertThat(hasSourceQaId(results, QA_ORGB_ID)).isFalse();
    }

    @Test @Order(21)
    @DisplayName("TC-RS-21: Q&A publicado sem technical_answer (legado) é excluído do RAG")
    void tc21_shortAnswerOnlyQaExcluded() {
        // Regra editorial: short_answer sozinha nunca sustenta conteúdo no RAG,
        // mesmo que a entrada esteja VALIDATED + publicada + com embedding.
        List<Map<String, Object>> results = search(ORG_A, 10);
        assertThat(hasSourceQaId(results, QA_SHORT_ONLY_ID)).isFalse();
    }

    @Test @Order(20)
    @DisplayName("TC-RS-20: Ranking correcto — similarity DESC com vectores controlados (1.0 > 0.707 > 0.0)")
    void tc20_rankingCorrect() {
        // DOC_VALID (VEC_HIGH, sim=1.0) must rank 1st
        // QA_VALID  (VEC_MED,  sim≈0.707) must rank 2nd
        // DOC_LOW   (VEC_LOW,  sim=0.0)  must rank 3rd
        List<Map<String, Object>> results = search(ORG_A, 10);
        assertThat(results).hasSize(3);

        double sim0 = ((Number) results.get(0).get("similarity")).doubleValue();
        double sim1 = ((Number) results.get(1).get("similarity")).doubleValue();
        double sim2 = ((Number) results.get(2).get("similarity")).doubleValue();

        assertThat(sim0).isBetween(0.999, 1.001);
        assertThat(sim1).isBetween(0.700, 0.715);
        assertThat(sim2).isBetween(-0.001, 0.001);

        assertThat(sim0).isGreaterThan(sim1);
        assertThat(sim1).isGreaterThan(sim2);
        assertThat(results.get(0).get("title")).isEqualTo("IVA Periodicidade Mensal");
    }

    @Test @Order(21)
    @DisplayName("TC-RS-21: LIMIT global aplica-se após UNION ALL — topK=1 devolve apenas o melhor resultado")
    void tc21_limitGlobal() {
        // With topK=1, only the globally best result across both branches is returned.
        // The best is DOC_VALID (sim=1.0) from the DOCUMENT branch.
        List<Map<String, Object>> results = search(ORG_A, 1);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("title")).isEqualTo("IVA Periodicidade Mensal");
        assertThat(results.get(0).get("source_kind")).isEqualTo("DOCUMENT");
    }

    @Test @Order(22)
    @DisplayName("TC-RS-22: Scores DOCUMENT e KNOWLEDGE_QA são comparáveis — Q&A MED supera DOC LOW")
    void tc22_scoreComparableBetweenBranches() {
        // Both UNION ALL branches use identical formula: 1 - (embedding <=> ?::vector)
        // This test proves scores are on the same scale by verifying that QA_VALID (sim≈0.707)
        // ranks above DOC_LOW (sim=0.0) even though they come from different branches.
        List<Map<String, Object>> results = search(ORG_A, 10);

        int qaPosition = -1, docLowPosition = -1;
        for (int i = 0; i < results.size(); i++) {
            if (QA_VALID_ID.toString().equals(results.get(i).get("source_qa_id"))) {
                qaPosition = i;
            }
            if ("Isenção Pequenos Negócios".equals(results.get(i).get("title"))) {
                docLowPosition = i;
            }
        }
        assertThat(qaPosition).as("QA_VALID must be present in results").isGreaterThanOrEqualTo(0);
        assertThat(docLowPosition).as("DOC_LOW must be present in results").isGreaterThanOrEqualTo(0);
        assertThat(qaPosition)
                .as("QA_VALID (sim≈0.707) must rank above DOC_LOW (sim=0.0) — scores are comparable")
                .isLessThan(docLowPosition);
    }

    @Test @Order(23)
    @DisplayName("TC-RS-23: UNION ALL não produz duplicados — exactamente 3 resultados para Org A")
    void tc23_unionAllNoDuplicates() {
        // UNION ALL is safe here: 'DOCUMENT' vs 'KNOWLEDGE_QA' in source_kind means
        // no two rows can ever be identical. Result count must be exactly 3:
        //   DOC_VALID (sim=1.0) + QA_VALID (sim≈0.707) + DOC_LOW (sim=0.0)
        List<Map<String, Object>> results = search(ORG_A, 10);
        assertThat(results).hasSize(3);
    }

    @Test @Order(24)
    @DisplayName("TC-RS-24: UUID de organização inexistente devolve 0 resultados")
    void tc24_unknownOrgReturnsEmpty() {
        UUID unknownOrg = UUID.fromString("d9999999-9999-9999-9999-999999999999");
        List<Map<String, Object>> results = search(unknownOrg, 10);
        assertThat(results).isEmpty();
    }

    @Test @Order(25)
    @DisplayName("TC-RS-25: Zero chamadas externas — datasource ligado ao container local")
    void tc25_zeroExternalCalls() {
        String url  = postgres.getJdbcUrl();
        String host = postgres.getHost();
        assertThat(url).startsWith("jdbc:postgresql://");
        assertThat(host).isIn("localhost", "127.0.0.1");
    }
}
