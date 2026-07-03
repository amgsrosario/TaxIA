package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.repository.AuditEventRepository;
import com.knowledgeflow.ingestion.atfaq.AtFaqImportService;
import com.knowledgeflow.ingestion.atfaq.AtFaqImportService.AtFaqRunResult;
import com.knowledgeflow.ingestion.atfaq.AtFaqIngestionRunRepository;
import com.knowledgeflow.ingestion.atfaq.AtFaqIngestionStatus;
import com.knowledgeflow.ingestion.atfaq.AtFaqPersistenceService;
import com.knowledgeflow.ingestion.atfaq.AtFaqPersistenceService.NewItemData;
import com.knowledgeflow.ingestion.atfaq.AtFaqRawItem;
import com.knowledgeflow.ingestion.atfaq.AtFaqRawItemRepository;
import com.knowledgeflow.ingestion.atfaq.AtFaqRunStatus;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * AT FAQ ingestion pilot over real PostgreSQL (Testcontainers) and a local
 * fixture HTTP server — zero external calls.
 *
 * Proves: RAW persistence, uniqueness, new/unchanged/changed FAQ handling,
 * previous-version preservation, possible removal after 2 misses, quarantined
 * import, no publication, no embeddings, organization isolation, audit,
 * idempotency, rollback atomicity, and item limits.
 *
 * Run: mvn verify -Ppgtest -Dit.test=AtFaqIngestionPostgresIT
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AtFaqIngestionPostgresIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    // ── Local fixture server (started before Spring so the port is known) ────

    static final String PREFIX = "/pt/apoio_contribuinte/questoes_frequentes";
    static HttpServer server;
    static String baseUrl;
    /** Path → fixture file. Mutable so tests can simulate source changes. */
    static final Map<String, String> routes = new ConcurrentHashMap<>();
    static final List<String> requestLog = new CopyOnWriteArrayList<>();

    static {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                requestLog.add(exchange.getRequestHeaders().getFirst("Host") + path);
                String fixture = routes.get(path);
                if (fixture == null) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                byte[] body = fixture(fixture);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
                exchange.close();
            });
            server.start();
            baseUrl = "http://localhost:" + server.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start fixture server", e);
        }
    }

    static byte[] fixture(String name) throws IOException {
        try (InputStream in = AtFaqIngestionPostgresIT.class.getResourceAsStream("/at-faq/" + name)) {
            if (in == null) throw new IllegalArgumentException("Fixture not found: " + name);
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("BASE_URL", baseUrl);
            return html.getBytes(StandardCharsets.UTF_8);
        }
    }

    @DynamicPropertySource
    static void atFaqProperties(DynamicPropertyRegistry registry) {
        registry.add("knowledgeflow.ingestion.at-faq.enabled", () -> "true");
        registry.add("knowledgeflow.ingestion.at-faq.base-url", () -> baseUrl);
        registry.add("knowledgeflow.ingestion.at-faq.allowed-hosts", () -> "localhost");
        registry.add("knowledgeflow.ingestion.at-faq.delay-ms", () -> "0");
        registry.add("knowledgeflow.ingestion.at-faq.max-retries", () -> "0");
    }

    @BeforeAll
    void resetRoutes() {
        routes.put(PREFIX + "/Pages/faqs.aspx", "index.html");
        routes.put(PREFIX + "/pages/faqs-90001.aspx", "categoria-deducao.html");
        routes.put(PREFIX + "/pages/faqs-90002.aspx", "categoria-taxas.html");
        routes.put(PREFIX + "/pages/faqs-90004.aspx", "categoria-faturacao.html");
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    // ── Fixtures under test ───────────────────────────────────────────────────

    @Autowired AtFaqImportService importService;
    @Autowired AtFaqPersistenceService persistenceService;
    @Autowired AtFaqRawItemRepository rawItemRepository;
    @Autowired AtFaqIngestionRunRepository runRepository;
    @Autowired KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired KnowledgeSourceReferenceRepository sourceRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired AuditEventRepository auditEventRepository;
    @Autowired JdbcTemplate jdbc;

    static Organization orgA;
    static Organization orgB;
    static UUID adminUserId;

    static final String AUTHORITY = "Autoridade Tributária e Aduaneira";

    @Autowired com.knowledgeflow.users.repository.UserRepository userRepository;

    @BeforeAll
    void createOrganizations() {
        orgA = organizationRepository.save(new Organization("Org A — Piloto AT FAQ", null));
        orgB = organizationRepository.save(new Organization("Org B — Isolamento", null));
        // audit_events.user_id has a FK to users — the acting admin must exist.
        adminUserId = userRepository.save(new com.knowledgeflow.users.entity.User(
                "atfaq-admin@pilot.test", "AT FAQ Pilot Admin", "not-a-real-hash")).getId();
    }

    private long qaCountFor(Organization organization) {
        return qaRepository.findByOrganizationId(organization.getId(),
                org.springframework.data.domain.Pageable.unpaged()).getTotalElements();
    }

    // =========================================================================
    // 1. Dry-run: full pipeline, zero writes
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Dry-run: classifica 7 FAQs novas e não escreve nada além do registo do run")
    void dryRunWritesNothing() {
        AtFaqRunResult result = importService.dryRun(orgA.getId(), adminUserId, null, null);

        assertThat(result.status()).isEqualTo(AtFaqRunStatus.COMPLETED);
        assertThat(result.report().pagesDiscovered()).isEqualTo(3);
        assertThat(result.report().faqsParsed()).isEqualTo(7);
        assertThat(result.report().newItems()).isEqualTo(7);
        assertThat(result.report().parseFailures()).isEqualTo(2);
        assertThat(result.report().dryRun()).isTrue();

        // Zero writes: RAW, Q&A, embeddings all untouched.
        assertThat(rawItemRepository.count()).isZero();
        assertThat(qaCountFor(orgA)).isZero();
        assertThat(embeddingCount()).isZero();
        // Only the run record exists.
        assertThat(runRepository.findByIdAndOrganizationId(result.runId(), orgA.getId())).isPresent();
    }

    @Test
    @Order(2)
    @DisplayName("Dry-run com limite de itens: trunca e reporta")
    void dryRunHonoursItemLimit() {
        AtFaqRunResult result = importService.dryRun(orgA.getId(), adminUserId, null, 3);
        assertThat(result.report().faqsParsed()).isEqualTo(3);
        assertThat(result.report().truncatedByLimit()).isTrue();
        assertThat(rawItemRepository.count()).isZero();
    }

    // =========================================================================
    // 2. Import: RAW persistence + quarantined Q&A
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("Importação: 7 itens RAW + 7 Q&A em quarentena, sem publicação nem embeddings")
    void importPersistsRawAndQuarantinedQa() {
        AtFaqRunResult result = importService.importRun(orgA.getId(), adminUserId, null, null);

        assertThat(result.status()).isEqualTo(AtFaqRunStatus.COMPLETED);
        assertThat(result.report().newItems()).isEqualTo(7);
        assertThat(result.report().importedToQuarantine()).isEqualTo(7);

        // RAW persistence with full metadata.
        List<AtFaqRawItem> items = rawItemRepository.findByOrganizationIdAndSupersededFalse(orgA.getId());
        assertThat(items).hasSize(7);
        AtFaqRawItem taxa = itemOf(orgA, "4001");
        assertThat(taxa.getIngestionStatus()).isEqualTo(AtFaqIngestionStatus.IMPORTED);
        assertThat(taxa.getCategory()).isEqualTo("IVA");
        assertThat(taxa.getSubcategory()).isEqualTo("Taxas");
        assertThat(taxa.getSourceAuthority()).isEqualTo(AUTHORITY);
        assertThat(taxa.getSourceUrl()).contains("faqs-90002.aspx");
        assertThat(taxa.getContentHash()).hasSize(64);
        assertThat(taxa.getParserVersion()).isEqualTo("1.0");
        assertThat(taxa.getAnswerRaw()).contains("6%").contains("verba 2.36");
        assertThat(taxa.getDetectedLegalReferences()).contains("CIVA");
        assertThat(taxa.getImportedQaId()).isNotNull();

        // Quarantined Q&A: IMPORTED, human review required, never published.
        KnowledgeQuestionAnswer qa = qaRepository
                .findByOrganizationIdAndSourceSystemAndExternalKey(orgA.getId(), "at-faq", "AT-FAQ-4001")
                .orElseThrow();
        assertThat(qa.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.IMPORTED);
        assertThat(qa.isRequiresHumanValidation()).isTrue();
        assertThat(qa.isPublished()).isFalse();
        assertThat(qa.getShortAnswer()).isNull();
        assertThat(qa.getTechnicalAnswer()).isNull();
        assertThat(qa.getOriginalAnswer()).contains("6%");
        assertThat(qa.getNotes()).contains("não é resposta canónica");

        // Official source reference attached.
        var sources = sourceRepository.findByQuestionAnswerId(qa.getId());
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).getSourceType()).isEqualTo(KnowledgeSourceType.OFFICIAL_FAQ);
        assertThat(sources.get(0).getTitle()).contains("Portal das Finanças");
        assertThat(sources.get(0).getUrl()).contains("faqs-90002.aspx");

        // No publication, no embeddings — quarantine is airtight.
        assertThat(embeddingCount()).isZero();
        assertThat(qaRepository.findPublishedForOrg(orgA.getId())).isEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("Idempotência: segunda importação não cria nada e marca tudo inalterado")
    void reimportIsIdempotent() {
        long rawBefore = rawItemRepository.count();
        long qaBefore = qaCountFor(orgA);

        AtFaqRunResult result = importService.importRun(orgA.getId(), adminUserId, null, null);

        assertThat(result.report().newItems()).isZero();
        assertThat(result.report().unchangedItems()).isEqualTo(7);
        assertThat(result.report().changedItems()).isZero();
        assertThat(result.report().importedToQuarantine()).isZero();
        assertThat(rawItemRepository.count()).isEqualTo(rawBefore);
        assertThat(qaCountFor(orgA)).isEqualTo(qaBefore);
    }

    // =========================================================================
    // 3. Change detection + version preservation
    // =========================================================================

    @Test
    @Order(5)
    @DisplayName("Alteração na fonte: nova versão CHANGED_AT_SOURCE, versão anterior preservada")
    void changedFaqCreatesNewVersionAndPreservesPrevious() {
        String previousHash = itemOf(orgA, "4001").getContentHash();
        routes.put(PREFIX + "/pages/faqs-90002.aspx", "categoria-taxas-v2.html");

        AtFaqRunResult result = importService.importRun(orgA.getId(), adminUserId, null, null);

        // 4001 changed, 4005 new; 4002/4004 unchanged (whitespace only); 4003 missing (1st miss).
        assertThat(result.report().changedItems()).isEqualTo(1);
        assertThat(result.report().newItems()).isEqualTo(1);
        assertThat(result.report().possiblyRemoved()).isZero();

        AtFaqRawItem current = itemOf(orgA, "4001");
        assertThat(current.getIngestionStatus()).isEqualTo(AtFaqIngestionStatus.CHANGED_AT_SOURCE);
        assertThat(current.isSourceChanged()).isTrue();
        assertThat(current.getContentHash()).isNotEqualTo(previousHash);
        assertThat(current.getPreviousVersionId()).isNotNull();

        AtFaqRawItem previous = rawItemRepository.findById(current.getPreviousVersionId()).orElseThrow();
        assertThat(previous.isSuperseded()).isTrue();
        assertThat(previous.getContentHash()).isEqualTo(previousHash);
        assertThat(previous.getAnswerRaw()).doesNotContain("Resposta actualizada");

        // Whitespace-only differences do NOT trigger a change (4002 stable hash).
        assertThat(itemOf(orgA, "4002").isSourceChanged()).isFalse();

        // 4003 absent once: registered as miss, not yet a removal candidate.
        AtFaqRawItem missing = itemOf(orgA, "4003");
        assertThat(missing.getConsecutiveMissCount()).isEqualTo(1);
        assertThat(missing.isSourceRemoved()).isFalse();
        assertThat(missing.getIngestionStatus()).isNotEqualTo(AtFaqIngestionStatus.POSSIBLY_REMOVED);
    }

    @Test
    @Order(6)
    @DisplayName("Remoção provável só após 2 execuções consecutivas sem a FAQ")
    void possibleRemovalAfterTwoConsecutiveMisses() {
        AtFaqRunResult result = importService.importRun(orgA.getId(), adminUserId, null, null);

        assertThat(result.report().possiblyRemoved()).isEqualTo(1);
        AtFaqRawItem missing = itemOf(orgA, "4003");
        assertThat(missing.getConsecutiveMissCount()).isEqualTo(2);
        assertThat(missing.isSourceRemoved()).isTrue();
        assertThat(missing.getIngestionStatus()).isEqualTo(AtFaqIngestionStatus.POSSIBLY_REMOVED);
        // Never deleted — history preserved.
        assertThat(missing.getAnswerRaw()).isNotBlank();
    }

    // =========================================================================
    // 4. Uniqueness + rollback atomicity
    // =========================================================================

    @Test
    @Order(7)
    @DisplayName("Unicidade: segundo item activo para o mesmo id oficial é rejeitado pela BD")
    void uniqueActiveItemPerOrgAuthorityAndFaqId() {
        Organization org = organizationRepository.findById(orgA.getId()).orElseThrow();
        AtFaqRawItem duplicate = new AtFaqRawItem(
                org, "4001", AUTHORITY, "IVA", "Taxas",
                "Pergunta duplicada?", "Resposta duplicada.",
                baseUrl + PREFIX + "/pages/faqs-90002.aspx", "Título",
                "0".repeat(64), "1.0", java.time.OffsetDateTime.now());

        assertThatThrownBy(() -> rawItemRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Order(8)
    @DisplayName("Rollback: falha na persistência RAW desfaz também a Q&A da mesma transacção")
    void importTransactionRollsBackAtomically() {
        Organization org = organizationRepository.findById(orgA.getId()).orElseThrow();
        // Active RAW item for 4999 already present → the unique index will fire.
        persist4999Seed(org);

        NewItemData data = new NewItemData(
                "4999", "Pergunta que vai falhar?", "Resposta que vai falhar.",
                "Taxas", baseUrl + PREFIX + "/pages/faqs-90002.aspx", "Título",
                "b".repeat(64), List.of(), List.of());

        assertThatThrownBy(() -> persistenceService.importNewItem(org, adminUserId, data))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The quarantined Q&A created inside the failed transaction must NOT survive.
        assertThat(qaRepository.findByOrganizationIdAndSourceSystemAndExternalKey(
                orgA.getId(), "at-faq", "AT-FAQ-4999")).isEmpty();
        // Only the seed row remains.
        assertThat(rawItemRepository
                .findByOrganizationIdAndSourceAuthorityAndOfficialFaqIdAndSupersededFalse(
                        orgA.getId(), AUTHORITY, "4999")).isPresent();
    }

    private void persist4999Seed(Organization org) {
        AtFaqRawItem seed = new AtFaqRawItem(
                org, "4999", AUTHORITY, "IVA", "Taxas",
                "Pergunta pré-existente?", "Resposta pré-existente.",
                baseUrl + PREFIX + "/pages/faqs-90002.aspx", "Título",
                "a".repeat(64), "1.0", java.time.OffsetDateTime.now());
        rawItemRepository.saveAndFlush(seed);
    }

    // =========================================================================
    // 5. Organization isolation
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("Isolamento: a importação da Org B não toca nos dados da Org A")
    void organizationIsolation() {
        long orgARawBefore = rawItemRepository.findByOrganizationIdAndSupersededFalse(orgA.getId()).size();
        long orgAQaBefore = qaCountFor(orgA);

        AtFaqRunResult result = importService.importRun(orgB.getId(), adminUserId, null, null);
        assertThat(result.status()).isEqualTo(AtFaqRunStatus.COMPLETED);

        // Org B has its own rows (current source state: v2 fixtures → 7 FAQs).
        assertThat(rawItemRepository.findByOrganizationIdAndSupersededFalse(orgB.getId()))
                .isNotEmpty()
                .allMatch(item -> item.getOrganization().getId().equals(orgB.getId()));

        // Org A untouched.
        assertThat(rawItemRepository.findByOrganizationIdAndSupersededFalse(orgA.getId()))
                .hasSize((int) orgARawBefore);
        assertThat(qaCountFor(orgA)).isEqualTo(orgAQaBefore);

        // Runs are not visible across organizations.
        assertThat(runRepository.findByIdAndOrganizationId(result.runId(), orgA.getId())).isEmpty();
    }

    // =========================================================================
    // 6. Audit + zero external calls
    // =========================================================================

    @Test
    @Order(10)
    @DisplayName("Auditoria: eventos de dry-run, importação, novas, alteradas e removidas")
    void auditTrailExists() {
        var events = auditEventRepository.findAll().stream()
                .filter(e -> e.getOrganizationId().equals(orgA.getId()))
                .map(e -> e.getAction())
                .toList();

        assertThat(events)
                .contains(AuditAction.AT_FAQ_DISCOVERY_STARTED)
                .contains(AuditAction.AT_FAQ_DISCOVERY_COMPLETED)
                .contains(AuditAction.AT_FAQ_DRY_RUN_COMPLETED)
                .contains(AuditAction.AT_FAQ_IMPORT_COMPLETED)
                .contains(AuditAction.AT_FAQ_ITEM_NEW)
                .contains(AuditAction.AT_FAQ_ITEM_CHANGED)
                .contains(AuditAction.AT_FAQ_ITEM_POSSIBLY_REMOVED)
                .contains(AuditAction.AT_FAQ_PARSE_FAILED);
    }

    @Test
    @Order(11)
    @DisplayName("Zero chamadas externas: todo o tráfego ficou no servidor local de fixtures")
    void zeroExternalCalls() {
        assertThat(requestLog).isNotEmpty();
        assertThat(requestLog).allMatch(entry -> entry.startsWith("localhost:"));
        // Only FAQ paths were requested — no crawling outside the authorized scope.
        assertThat(requestLog).allMatch(entry -> entry.contains(PREFIX));
        // The malicious external link inside a FAQ answer was recorded as data,
        // never followed: it can't appear in the request log by construction
        // (allowlist), and the fixture server never saw a phishing path.
        assertThat(requestLog).noneMatch(entry -> entry.contains("phishing"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private AtFaqRawItem itemOf(Organization org, String officialFaqId) {
        return rawItemRepository
                .findByOrganizationIdAndSourceAuthorityAndOfficialFaqIdAndSupersededFalse(
                        org.getId(), AUTHORITY, officialFaqId)
                .orElseThrow(() -> new AssertionError("Item not found: " + officialFaqId));
    }

    private long embeddingCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_qa_embeddings", Long.class);
        return count == null ? 0 : count;
    }
}
