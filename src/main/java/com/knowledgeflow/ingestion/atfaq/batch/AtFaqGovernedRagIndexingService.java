package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.rag.KnowledgeQaEmbeddingIndexer;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.organizations.entity.Organization;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Governed AT-FAQ RAG indexing service (Bloco E — E9A).
 *
 * <p>Frase-mestra: "Indexar não é escalar. É dar voz controlada a um único conhecimento publicado."
 *
 * <p>Given the output of E8B.3 (a real governed publication run), this service gives <em>controlled
 * voice</em> to exactly <b>one</b> published, RAG-eligible, LOW-risk Q&amp;A by writing its embedding
 * through the real {@link KnowledgeQaEmbeddingIndexer} contract. It is not a batch, not a scheduler
 * and not an endpoint: every call indexes a single Q&amp;A, and any further published items in the
 * same publication result are deferred to E9B (small governed batch).
 *
 * <p>It is meant to run against an isolated Testcontainers database. There, the injected indexer is
 * the real {@code KnowledgeQaEmbeddingIndexerImpl} constructed with a deterministic in-test
 * embedding, so E9A proves the genuine RAG/pgvector cycle — an embedding row in
 * {@code knowledge_qa_embeddings} that {@code RagSearchService} retrieves — <em>without</em> calling
 * the real embedding model and without ever touching the real pilot base or an external provider.
 *
 * <p>Indexing guards (a Q&amp;A is indexed only if <b>all</b> hold):
 * <ol>
 *   <li>the item was published in E8B.3 ({@code published == true}) with a {@code knowledgeQaId};</li>
 *   <li>the publication item carried no blocking reasons and was governed-publication eligible;</li>
 *   <li>the Q&amp;A exists and belongs to the supplied organization;</li>
 *   <li>{@code isPublished() == true};</li>
 *   <li>{@code curationStatus == VALIDATED};</li>
 *   <li>{@code isEligibleForRag() == true};</li>
 *   <li>a non-blank technical answer is present;</li>
 *   <li>at least one source reference exists;</li>
 *   <li>risk level is {@code LOW}.</li>
 * </ol>
 *
 * <p>Idempotency: the real indexer upserts on {@code knowledge_qa_id}, so indexing the same Q&amp;A
 * twice leaves exactly one embedding row.
 */
@Service
public class AtFaqGovernedRagIndexingService {

    private static final Logger log = LoggerFactory.getLogger(AtFaqGovernedRagIndexingService.class);

    private static final AtFaqRagIndexingMode MODE = AtFaqRagIndexingMode.TEST_ISOLATED_SINGLE_QA;

    private static final List<String> NEXT_ACTIONS = List.of(
            "E9B: lote governado pequeno de indexação sob o mesmo mecanismo controlado.",
            "E10: rollback/despublicação/desindexação governada.");

    private final KnowledgeQaEmbeddingIndexer indexer;
    private final KnowledgeQuestionAnswerRepository qaRepository;
    private final KnowledgeSourceReferenceRepository sourceRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public AtFaqGovernedRagIndexingService(
            KnowledgeQaEmbeddingIndexer indexer,
            KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeSourceReferenceRepository sourceRepository,
            JdbcTemplate jdbcTemplate) {
        this(indexer, qaRepository, sourceRepository, jdbcTemplate, Clock.systemUTC());
    }

    /** Test seam: inject a fixed {@link Clock}. */
    AtFaqGovernedRagIndexingService(
            KnowledgeQaEmbeddingIndexer indexer,
            KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeSourceReferenceRepository sourceRepository,
            JdbcTemplate jdbcTemplate,
            Clock clock) {
        this.indexer = indexer;
        this.qaRepository = qaRepository;
        this.sourceRepository = sourceRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Index exactly one published Q&amp;A from a governed publication run, in an isolated DB.
     *
     * @param publicationResult output of the E8B.3 governed publication
     * @param organization      the owning organization (never the real pilot base outside tests)
     * @param indexedBy         actor recorded as the indexing requester
     */
    public AtFaqRagIndexingResult indexSinglePublishedQa(
            AtFaqGovernedPublicationExecutionResult publicationResult,
            Organization organization,
            String indexedBy) {

        Instant indexedAt = clock.instant();

        if (publicationResult == null) {
            return emptyResult(null, indexedAt, indexedBy,
                    "Resultado de publicação ausente.",
                    "Fornecer um AtFaqGovernedPublicationExecutionResult válido.");
        }
        if (organization == null || organization.getId() == null) {
            return emptyResult(publicationResult.batchId(), indexedAt, indexedBy,
                    "Organização ausente.",
                    "Fornecer uma organização válida (nunca a base real do piloto).");
        }

        UUID organizationId = organization.getId();

        List<AtFaqGovernedPublicationExecutionItemResult> published = publicationResult.itemResults()
                .stream()
                .filter(AtFaqGovernedPublicationExecutionItemResult::published)
                .toList();

        int totalPublishedItems = published.size();

        if (published.isEmpty()) {
            return emptyResult(publicationResult.batchId(), indexedAt, indexedBy,
                    "Nenhum item publicado na publicação fornecida — nada a indexar.",
                    "Executar E8B.3 primeiro; E9A indexa um único Q&A já publicado.");
        }

        // Single-Q&A policy: index the first published item; defer the rest to E9B.
        AtFaqGovernedPublicationExecutionItemResult target = published.get(0);
        List<AtFaqGovernedPublicationExecutionItemResult> deferred =
                published.subList(1, published.size());

        List<AtFaqRagIndexingItemResult> itemResults = new ArrayList<>();
        AtFaqRagIndexingItemResult targetResult = indexTarget(target, organizationId);
        itemResults.add(targetResult);
        for (AtFaqGovernedPublicationExecutionItemResult other : deferred) {
            itemResults.add(deferredItem(other));
        }

        int indexed = targetResult.indexed() ? 1 : 0;
        int eligibleForIndexing = targetResult.eligibleForIndexing() ? 1 : 0;
        int blocked = targetResult.eligibleForIndexing() ? 0 : 1;
        int skipped = deferred.size();
        int embeddingRows = targetResult.embeddingRows();
        int ragExpected = targetResult.ragExpectedToRetrieve() ? 1 : 0;

        AtFaqRagIndexingTotals totals = new AtFaqRagIndexingTotals(
                totalPublishedItems, eligibleForIndexing, indexed, skipped, blocked,
                embeddingRows, ragExpected);

        List<String> globalWarnings = new ArrayList<>();
        if (!deferred.isEmpty()) {
            globalWarnings.add("Política E9A single-Q&A: " + deferred.size()
                    + " item(s) publicado(s) adicional(is) diferido(s) para E9B.");
        }

        return new AtFaqRagIndexingResult(
                publicationResult.batchId(), indexedAt, indexedBy, MODE,
                totals, itemResults, globalWarnings, List.of(), NEXT_ACTIONS);
    }

    // ------------------------------------------------------------------------------------------

    /** Build the inspectable indexing decision from the publication item (cheap, pre-DB checks). */
    private AtFaqRagIndexingCommand plan(AtFaqGovernedPublicationExecutionItemResult item) {
        List<String> guardChecks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> blocking = new ArrayList<>();

        if (item.knowledgeQaId() == null) {
            blocking.add("Item publicado sem knowledgeQaId.");
        } else {
            guardChecks.add("Item publicado com knowledgeQaId.");
        }
        if (!item.published()) {
            blocking.add("Item não publicado em E8B.3.");
        } else {
            guardChecks.add("Publicado em E8B.3.");
        }
        if (!item.eligibleForGovernedPublication()) {
            blocking.add("Item não foi elegível para publicação governada.");
        } else {
            guardChecks.add("Elegível para publicação governada.");
        }
        if (!item.blockingReasons().isEmpty()) {
            blocking.add("Item carrega razões bloqueadoras da publicação.");
        }

        boolean index = blocking.isEmpty();
        return new AtFaqRagIndexingCommand(
                item.externalId(),
                item.knowledgeQaId(),
                null, // normalizedQuestion resolved from the entity during attemptIndex
                index,
                true,  // singleQaOnly
                false, // productionDataAllowed
                guardChecks,
                warnings,
                blocking);
    }

    /** Run DB-level guards and, if all pass, index the single target Q&A through the real indexer. */
    private AtFaqRagIndexingItemResult indexTarget(
            AtFaqGovernedPublicationExecutionItemResult item, UUID organizationId) {

        AtFaqRagIndexingCommand command = plan(item);
        if (!command.index()) {
            return blockedItem(item, null, command.blockingReasons());
        }

        UUID qaId = command.knowledgeQaId();
        Optional<KnowledgeQuestionAnswer> found = qaRepository.findById(qaId);
        if (found.isEmpty()) {
            return blockedItem(item, null, List.of("Q&A não encontrada na BD isolada."));
        }
        KnowledgeQuestionAnswer qa = found.get();

        List<String> blocking = new ArrayList<>();
        if (qa.getOrganization() == null || !organizationId.equals(qa.getOrganization().getId())) {
            blocking.add("Q&A não pertence à organização fornecida.");
        }
        if (!qa.isPublished()) {
            blocking.add("Q&A não está publicada na BD isolada.");
        }
        if (qa.getCurationStatus() != KnowledgeCurationStatus.VALIDATED) {
            blocking.add("Estado de curadoria != VALIDATED: " + qa.getCurationStatus() + ".");
        }
        if (qa.getRiskLevel() != KnowledgeRiskLevel.LOW) {
            blocking.add("Risco != LOW: " + qa.getRiskLevel() + ".");
        }
        if (isBlank(qa.getTechnicalAnswer())) {
            blocking.add("Sem resposta técnica.");
        }
        if (sourceRepository.countByQuestionAnswerId(qaId) == 0) {
            blocking.add("Sem qualquer fonte associada.");
        }
        if (!qa.isEligibleForRag()) {
            blocking.add("Entidade não elegível para RAG (isEligibleForRag()==false).");
        }

        if (!blocking.isEmpty()) {
            return blockedItem(item, qa, blocking);
        }

        String question = firstNonBlank(qa.getNormalizedQuestion(), qa.getOriginalQuestion());
        String topic = qa.getTopic() != null ? qa.getTopic().name() : null;

        // Effective indexing through the real KnowledgeQaEmbeddingIndexer contract. In the isolated
        // test this is the real KnowledgeQaEmbeddingIndexerImpl SQL fed by a deterministic embedding.
        indexer.index(qaId, question, qa.getTechnicalAnswer(), topic);

        int rows = countEmbeddingRows(qaId);
        boolean embeddingPresent = rows >= 1;
        boolean ragExpected = embeddingPresent; // published + RAG-eligible + embedding present
        if (!embeddingPresent) {
            // Defensive: the indexer ran but no row is visible — report, never claim a false success.
            log.warn("Indexação não produziu embedding observável para qaId={}", qaId);
            return new AtFaqRagIndexingItemResult(
                    item.externalId(), qaId, false, false, false, false, 0,
                    qa.getNormalizedQuestion(),
                    List.of("Indexador executou mas não há linha de embedding visível."),
                    List.of("Indexação sem efeito observável na BD isolada."),
                    List.of("Investigar o mecanismo de embedding antes de prosseguir para E9B."));
        }

        log.debug("E9A indexou qaId={} — embeddingRows={}", qaId, rows);
        return new AtFaqRagIndexingItemResult(
                item.externalId(),
                qaId,
                true,  // eligibleForIndexing
                true,  // indexed
                true,  // embeddingPresent
                ragExpected,
                rows,
                qa.getNormalizedQuestion(),
                List.of(),
                List.of(),
                List.of("Indexado em BD isolada com embedding determinístico de teste. "
                        + "RAG pode agora recuperar este Q&A."));
    }

    private AtFaqRagIndexingItemResult deferredItem(AtFaqGovernedPublicationExecutionItemResult item) {
        int rows = item.knowledgeQaId() == null ? 0 : countEmbeddingRows(item.knowledgeQaId());
        return new AtFaqRagIndexingItemResult(
                item.externalId(),
                item.knowledgeQaId(),
                false, // eligibleForIndexing — deferred by the single-Q&A policy, not indexed here
                false, // indexed
                rows >= 1,
                false, // ragExpectedToRetrieve — this run did not index it
                rows,
                null,
                List.of("Diferido pela política E9A single-Q&A."),
                List.of(),
                List.of("Indexação em lote governado fica para E9B."));
    }

    private AtFaqRagIndexingItemResult blockedItem(
            AtFaqGovernedPublicationExecutionItemResult item,
            KnowledgeQuestionAnswer qa,
            List<String> blocking) {
        int rows = item.knowledgeQaId() == null ? 0 : countEmbeddingRows(item.knowledgeQaId());
        return new AtFaqRagIndexingItemResult(
                item.externalId(),
                item.knowledgeQaId(),
                false, // eligibleForIndexing
                false, // indexed
                rows >= 1,
                false,
                rows,
                qa != null ? qa.getNormalizedQuestion() : item.externalId(),
                List.of(),
                List.copyOf(blocking),
                List.of("Corrigir a causa do bloqueio antes de indexar. Indexação recusada."));
    }

    private AtFaqRagIndexingResult emptyResult(
            String batchId, Instant indexedAt, String indexedBy,
            String blockingError, String nextAction) {
        return new AtFaqRagIndexingResult(
                batchId, indexedAt, indexedBy, MODE,
                new AtFaqRagIndexingTotals(0, 0, 0, 0, 0, 0, 0),
                List.of(), List.of(), List.of(blockingError), List.of(nextAction));
    }

    private int countEmbeddingRows(UUID qaId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                Integer.class, qaId.toString());
        return count == null ? 0 : count;
    }

    private static String firstNonBlank(String primary, String fallback) {
        return !isBlank(primary) ? primary : fallback;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
