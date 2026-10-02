package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowledgeflow.ai.AIService;
import com.knowledgeflow.common.error.BusinessException;
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
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerPublicationService;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.rag.EmbeddingService;
import com.knowledgeflow.rag.RagSearchService;
import com.knowledgeflow.rag.RagSearchService.RetrievedCase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * Integridade pós-validação (ADR-005) em PostgreSQL real + pgvector: o RAG nunca devolve conteúdo
 * diferente do validado da versão publicada, e as corridas reais (lost update, edição vs validação,
 * edição vs publicação, substituições concorrentes) mantêm os invariantes.
 * Run: mvn verify -Ppgtest -Dit.test=PublishedContentIntegrityPostgresIT
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(PublishedContentIntegrityPostgresIT.InfraConfig.class)
class PublishedContentIntegrityPostgresIT {

    @TestConfiguration
    static class InfraConfig {
        static final String VEC;

        static {
            StringBuilder sb = new StringBuilder("[1.0");
            for (int i = 1; i < 768; i++) sb.append(",0.0");
            VEC = sb.append(']').toString();
        }

        @Bean
        @Primary
        EmbeddingService controlledEmbeddingService() {
            Float[] arr = new Float[768];
            arr[0] = 1.0f;
            for (int i = 1; i < 768; i++) arr[i] = 0.0f;
            List<Float> vec = List.of(arr);
            return new EmbeddingService() {
                @Override public List<Float> embedQuery(String text) { return vec; }
                @Override public List<Float> embedPassage(String text) { return vec; }
            };
        }

        @Bean
        @Primary
        KnowledgeQaEmbeddingIndexer jdbcIndexer(JdbcTemplate jdbc) {
            return new KnowledgeQaEmbeddingIndexer() {
                @Override
                public void index(UUID qaId, String question, String answer, String topic) {
                    jdbc.update("""
                            INSERT INTO knowledge_qa_embeddings (knowledge_qa_id, embedding)
                            VALUES (?::uuid, ?::vector)
                            ON CONFLICT (knowledge_qa_id) DO UPDATE SET embedding = EXCLUDED.embedding
                            """, qaId.toString(), VEC);
                }

                @Override
                public void remove(UUID qaId) {
                    jdbc.update("DELETE FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid", qaId.toString());
                }
            };
        }
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final UUID USER = UUID.fromString("c5000000-0000-0000-0000-000000000010");

    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeQuestionAnswerCurationService curationService;
    @Autowired KnowledgeQuestionAnswerPublicationService publicationService;
    @Autowired KnowledgeQuestionAnswerRepository qaRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired RagSearchService ragSearchService;
    @MockBean AIService aiService;

    @BeforeAll
    void users() {
        jdbc.update("INSERT INTO users(id, email, full_name, password_hash, status, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,NOW(),NOW())", USER, "editor@integrity.test", "Editor", "$2a$10$dummy", "ACTIVE");
    }

    @Test
    @DisplayName("RAG invariant: edição material recusada; rascunho invisível; só depois de substituir o RAG vê a v2")
    void ragServesOnlyValidatedPublishedContent() {
        UUID org = newOrg();
        UUID v1 = validatedWithSource(org, "RAG-1", "Resposta técnica validada v1.");
        publicationService.publish(org, USER, "Publicador", v1);
        assertThat(ragContents(org)).containsExactly("Resposta técnica validada v1.");

        // edição material na publicada → recusada; o RAG não muda
        KnowledgeQuestionAnswer pub = qaRepository.findById(v1).orElseThrow();
        assertThatThrownBy(() -> curationService.updateCuration(org, USER, v1,
                request(pub, "Conteúdo não validado.", pub.getSubtopic(), pub.getVersion())))
                .isInstanceOf(BusinessException.class).hasMessageContaining("new version");
        assertThatThrownBy(() -> curationService.updateCuration(org, USER, v1,
                request(pub, pub.getTechnicalAnswer(), "Outro subtema", pub.getVersion())))
                .isInstanceOf(BusinessException.class);
        assertThat(ragContents(org)).containsExactly("Resposta técnica validada v1.");

        // nova versão com conteúdo novo: o RAG continua a servir só a v1
        UUID v2 = publicationService.createNewVersion(org, USER, "Editor", v1, "Resposta técnica v2 em revisão.").getId();
        assertThat(ragContents(org)).containsExactly("Resposta técnica validada v1.");
        assertThat(ragIds(org)).containsExactly(v1);
        curationService.validate(org, USER, "Revisor v2", v2);
        assertThat(ragIds(org)).containsExactly(v1);

        publicationService.publishReplacing(org, USER, "Publicador", v2, v1);
        assertThat(ragContents(org)).containsExactly("Resposta técnica v2 em revisão.");
        assertThat(ragIds(org)).containsExactly(v2);
        assertThat(publishedCount(v1, v2)).isEqualTo(1);
    }

    @Test
    @DisplayName("Lost update: o segundo editor com versão antiga recebe 409; o conteúdo do primeiro fica")
    void staleEditIsRejected() {
        UUID org = newOrg();
        UUID id = pendingWithSource(org, "LOST-1");
        KnowledgeQuestionAnswer start = qaRepository.findById(id).orElseThrow();
        int version = start.getVersion();

        curationService.updateCuration(org, USER, id, request(start, "Primeiro editor.", start.getSubtopic(), version));
        assertThatThrownBy(() -> curationService.updateCuration(org, USER, id,
                request(start, "Segundo editor.", start.getSubtopic(), version)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("expected version");
        assertThat(qaRepository.findById(id).orElseThrow().getTechnicalAnswer()).isEqualTo("Primeiro editor.");
    }

    @Test
    @DisplayName("Edição vs validação concorrentes: nunca fica VALIDATED com conteúdo editado depois da validação")
    void editVersusValidate() throws Exception {
        for (int round = 0; round < 5; round++) {
            UUID org = newOrg();
            UUID id = pendingWithSource(org, "EVV-" + round);
            KnowledgeQuestionAnswer start = qaRepository.findById(id).orElseThrow();
            List<Boolean> results = run(
                    () -> { curationService.updateCuration(org, USER, id,
                            request(start, "Editado em corrida.", start.getSubtopic(), start.getVersion())); return true; },
                    () -> { curationService.validate(org, USER, "Revisor", id); return true; });
            KnowledgeQuestionAnswer end = qaRepository.findById(id).orElseThrow();
            boolean edited = results.get(0);
            boolean validated = results.get(1);
            assertThat(validated).isTrue(); // a validação nunca é bloqueada por uma edição de PENDING_REVIEW
            if (edited && end.getCurationStatus() == KnowledgeCurationStatus.VALIDATED) {
                // edição primeiro (lock), validação depois: o validado é o conteúdo editado
                assertThat(end.getTechnicalAnswer()).isEqualTo("Editado em corrida.");
            }
            if (!edited) {
                // validação primeiro: a edição com versão antiga é recusada
                assertThat(end.getTechnicalAnswer()).isEqualTo("Técnica EVV-" + round + ".");
            }
        }
    }

    @Test
    @DisplayName("Edição material vs publicação concorrentes: nunca publicado com conteúdo não validado")
    void editVersusPublish() throws Exception {
        for (int round = 0; round < 5; round++) {
            UUID org = newOrg();
            UUID id = validatedWithSource(org, "EVP-" + round, "Técnica validada EVP.");
            KnowledgeQuestionAnswer start = qaRepository.findById(id).orElseThrow();
            run(() -> { curationService.updateCuration(org, USER, id,
                            request(start, "Editado em corrida.", start.getSubtopic(), start.getVersion())); return true; },
                    () -> { publicationService.publish(org, USER, "Publicador", id); return true; });
            KnowledgeQuestionAnswer end = qaRepository.findById(id).orElseThrow();
            if (end.isPublished()) {
                assertThat(end.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.VALIDATED);
                assertThat(end.getTechnicalAnswer()).isEqualTo("Técnica validada EVP.");
                assertThat(ragContents(org)).containsExactly("Técnica validada EVP.");
            } else {
                assertThat(end.getCurationStatus()).isEqualTo(KnowledgeCurationStatus.PENDING_REVIEW);
                assertThat(ragContents(org)).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("Duas substituições concorrentes: exactamente uma vence; uma só versão publicada na linhagem")
    void concurrentReplacements() throws Exception {
        UUID org = newOrg();
        UUID v1 = validatedWithSource(org, "REP-1", "Técnica v1.");
        publicationService.publish(org, USER, "Publicador", v1);
        UUID v2 = publicationService.createNewVersion(org, USER, "Editor", v1, "Técnica v2.").getId();
        curationService.validate(org, USER, "Revisor", v2);

        List<Boolean> results = run(
                () -> { publicationService.publishReplacing(org, USER, "A", v2, v1); return true; },
                () -> { publicationService.publishReplacing(org, USER, "B", v2, v1); return true; });
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(publishedCount(v1, v2)).isEqualTo(1);
        assertThat(qaRepository.findById(v2).orElseThrow().isPublished()).isTrue();
        assertThat(ragIds(org)).containsExactly(v2);
    }

    @Test
    @DisplayName("Publicações concorrentes de duas versões da mesma linhagem: só uma fica publicada")
    void concurrentPlainPublishesInOneLineage() throws Exception {
        for (int round = 0; round < 5; round++) {
            UUID org = newOrg();
            UUID a = validatedWithSource(org, "LIN-" + round, "Técnica A.");
            publicationService.publish(org, USER, "Publicador", a);
            UUID b = publicationService.createNewVersion(org, USER, "Editor", a, "Técnica B.").getId();
            curationService.validate(org, USER, "Revisor", b);
            publicationService.unpublish(org, USER, a);

            run(() -> { publicationService.publish(org, USER, "P1", a); return true; },
                    () -> { publicationService.publish(org, USER, "P2", b); return true; });
            assertThat(publishedCount(a, b)).isEqualTo(1);
            assertThat(ragIds(org)).hasSize(1);
        }
    }

    // -------------------------------------------------------------------------------------------

    private List<Boolean> run(Callable<Boolean> a, Callable<Boolean> b) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (Callable<Boolean> action : List.of(a, b)) {
            futures.add(executor.submit(() -> { barrier.await(); return action.call(); }));
        }
        List<Boolean> results = new ArrayList<>();
        for (Future<Boolean> f : futures) {
            try {
                results.add(Boolean.TRUE.equals(f.get()));
            } catch (Exception loser) {
                results.add(false); // 409 / transição inválida / lock — perdedor legítimo
            }
        }
        executor.shutdown();
        return results;
    }

    private UUID newOrg() {
        return organizationRepository.save(new Organization("Org Integridade " + UUID.randomUUID(), null)).getId();
    }

    private UUID pendingWithSource(UUID orgId, String key) {
        Organization org = organizationRepository.findById(orgId).orElseThrow();
        KnowledgeQuestionAnswer qa = new KnowledgeQuestionAnswer(org, "Pergunta " + key + "?", "Original.", "int-pg", key);
        qa.updateCuration(null, "Curta " + key + ".", "Técnica " + key + ".", KnowledgeTopic.IRS, "Categoria F", "PT",
                KnowledgeRiskLevel.LOW, false, null, null, null);
        qa.markPendingReview();
        UUID id = qaRepository.save(qa).getId();
        curationService.addSource(orgId, USER, id, new SourceReferenceRequest(KnowledgeSourceType.LEGISLATION, "CIRS",
                "art. 41.º", null, null, null, null, null, null));
        return id;
    }

    private UUID validatedWithSource(UUID orgId, String key, String technical) {
        UUID id = pendingWithSource(orgId, key);
        KnowledgeQuestionAnswer qa = qaRepository.findById(id).orElseThrow();
        curationService.updateCuration(orgId, USER, id, request(qa, technical, qa.getSubtopic(), qa.getVersion()));
        curationService.validate(orgId, USER, "Revisor", id);
        return id;
    }

    private static KnowledgeQaCurationRequest request(KnowledgeQuestionAnswer qa, String technical, String subtopic,
            int expectedVersion) {
        return new KnowledgeQaCurationRequest(qa.getNormalizedQuestion(), qa.getShortAnswer(), technical, qa.getTopic(),
                subtopic, qa.getJurisdiction(), qa.getRiskLevel(), qa.isRequiresHumanValidation(), qa.getValidFrom(),
                qa.getValidTo(), qa.getNotes(), expectedVersion);
    }

    private List<String> ragContents(UUID org) {
        return ragSearchService.findSimilar(org, "Pergunta?").stream().map(RetrievedCase::content).toList();
    }

    private List<UUID> ragIds(UUID org) {
        return ragSearchService.findSimilar(org, "Pergunta?").stream().map(RetrievedCase::sourceQaId).toList();
    }

    private long publishedCount(UUID... ids) {
        long n = 0;
        for (UUID id : ids) if (qaRepository.findById(id).orElseThrow().isPublished()) n++;
        return n;
    }
}
