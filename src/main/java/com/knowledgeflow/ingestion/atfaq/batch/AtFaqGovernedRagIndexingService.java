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
 * Governed AT-FAQ RAG indexing service (Bloco E — E9A/E9B).
 *
 * <p>E9A frase-mestra: "Indexar não é escalar. É dar voz controlada a um único conhecimento
 * publicado." E9B frase-mestra: "Indexar vários não é escalar livremente. É provar que o lote
 * obedece aos mesmos guardas do caso único."
 *
 * <p>Given the output of E8B.3 (a real governed publication run), this service gives <em>controlled
 * voice</em> to published, RAG-eligible, LOW-risk Q&amp;A by writing their embeddings through the
 * real {@link KnowledgeQaEmbeddingIndexer} contract. It is not a scheduler and not an endpoint:
 * <ul>
 *   <li>{@link #indexSinglePublishedQa} (E9A) indexes exactly one Q&amp;A and defers any other
 *       published items to the small batch;</li>
 *   <li>{@link #indexSmallPublishedBatch} (E9B) indexes a small governed batch — more than one, at
 *       most {@link AtFaqRagIndexingTotals#MAX_SMALL_BATCH} — under the <b>same</b> per-Q&amp;A
 *       guards, plus a hard batch ceiling. Eligible items beyond the limit are deferred, never
 *       dropped silently.</li>
 * </ul>
 *
 * <p>It is meant to run against an isolated Testcontainers database. There, the injected indexer is
 * the real {@code KnowledgeQaEmbeddingIndexerImpl} constructed with a deterministic in-test
 * embedding, so these methods prove the genuine RAG/pgvector cycle — embedding rows in
 * {@code knowledge_qa_embeddings} that {@code RagSearchService} retrieves — <em>without</em> calling
 * the real embedding model and without ever touching the real pilot base or an external provider.
 *
 * <p>Indexing guards (a Q&amp;A is indexed only if <b>all</b> hold, identical for single and batch):
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
 * twice leaves exactly one embedding row — a re-run of a batch keeps exactly N rows.
 */
@Service
public class AtFaqGovernedRagIndexingService {

    private static final Logger log = LoggerFactory.getLogger(AtFaqGovernedRagIndexingService.class);

    private static final AtFaqRagIndexingMode SINGLE_MODE =
            AtFaqRagIndexingMode.TEST_ISOLATED_SINGLE_QA;
    private static final AtFaqRagIndexingMode BATCH_MODE =
            AtFaqRagIndexingMode.SMALL_BATCH_TEST_ISOLATED;

    /** Smallest batch that is still a batch: one Q&A uses {@link #indexSinglePublishedQa}. */
    private static final int MIN_SMALL_BATCH = 2;
    /** Hard ceiling on a governed small batch. */
    private static final int MAX_SMALL_BATCH = AtFaqRagIndexingTotals.MAX_SMALL_BATCH;

    private static final List<String> NEXT_ACTIONS = List.of(
            "E9C: lote real/piloto controlado de indexação sob o mesmo mecanismo governado.",
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
     * Index exactly one published Q&amp;A from a governed publication run, in an isolated DB (E9A).
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
            return emptyResult(SINGLE_MODE, null, indexedAt, indexedBy, 1, 1,
                    "Resultado de publicação ausente.",
                    "Fornecer um AtFaqGovernedPublicationExecutionResult válido.");
        }
        if (organization == null || organization.getId() == null) {
            return emptyResult(SINGLE_MODE, publicationResult.batchId(), indexedAt, indexedBy, 1, 1,
                    "Organização ausente.",
                    "Fornecer uma organização válida (nunca a base real do piloto).");
        }

        UUID organizationId = organization.getId();
        List<AtFaqGovernedPublicationExecutionItemResult> published = publishedItems(publicationResult);
        int totalPublishedItems = published.size();

        if (published.isEmpty()) {
            return emptyResult(SINGLE_MODE, publicationResult.batchId(), indexedAt, indexedBy, 1, 1,
                    "Nenhum item publicado na publicação fornecida — nada a indexar.",
                    "Executar E8B.3 primeiro; E9A indexa um único Q&A já publicado.");
        }

        // Single-Q&A policy: index the first published item; defer the rest to the small batch.
        AtFaqGovernedPublicationExecutionItemResult target = published.get(0);
        List<AtFaqGovernedPublicationExecutionItemResult> deferred =
                published.subList(1, published.size());

        List<AtFaqRagIndexingItemResult> itemResults = new ArrayList<>();
        AtFaqRagIndexingItemResult targetResult = indexOne(target, organizationId);
        itemResults.add(targetResult);
        for (AtFaqGovernedPublicationExecutionItemResult other : deferred) {
            itemResults.add(deferredBySinglePolicy(other));
        }

        int indexed = targetResult.indexed() ? 1 : 0;
        int eligibleForIndexing = targetResult.eligibleForIndexing() ? 1 : 0;
        int blocked = targetResult.eligibleForIndexing() ? 0 : 1;
        int skipped = deferred.size();
        int embeddingRows = targetResult.embeddingRows();
        int ragExpected = targetResult.ragExpectedToRetrieve() ? 1 : 0;

        AtFaqRagIndexingTotals totals = new AtFaqRagIndexingTotals(
                SINGLE_MODE, totalPublishedItems, eligibleForIndexing, indexed, skipped, blocked,
                embeddingRows, ragExpected);

        List<String> globalWarnings = new ArrayList<>();
        if (!deferred.isEmpty()) {
            globalWarnings.add("Política E9A single-Q&A: " + deferred.size()
                    + " item(s) publicado(s) adicional(is) diferido(s) para E9B.");
        }

        return new AtFaqRagIndexingResult(
                publicationResult.batchId(), indexedAt, indexedBy, SINGLE_MODE,
                totals, itemResults, globalWarnings, List.of(), NEXT_ACTIONS, 1, 1);
    }

    /**
     * Index a small governed batch of published Q&amp;A from a governed publication run, in an
     * isolated DB (E9B). Every per-Q&amp;A guard from {@link #indexSinglePublishedQa} applies
     * unchanged; the only addition is a hard batch ceiling. Eligible items beyond {@code maxItems}
     * are deferred (reported, never dropped silently).
     *
     * @param publicationResult output of the E8B.3 governed publication
     * @param organization      the owning organization (never the real pilot base outside tests)
     * @param indexedBy         actor recorded as the indexing requester
     * @param maxItems          batch limit; must be in [{@value #MIN_SMALL_BATCH},
     *                          {@value #MAX_SMALL_BATCH}] — 1 belongs to the single flow, &gt;3 is refused
     */
    public AtFaqRagIndexingResult indexSmallPublishedBatch(
            AtFaqGovernedPublicationExecutionResult publicationResult,
            Organization organization,
            String indexedBy,
            int maxItems) {

        Instant indexedAt = clock.instant();
        String batchId = publicationResult != null ? publicationResult.batchId() : null;

        // --- Controlled-configuration guards: refuse to index anything on an invalid batch limit ---
        if (maxItems < MIN_SMALL_BATCH) {
            return emptyResult(BATCH_MODE, batchId, indexedAt, indexedBy, maxItems, 0,
                    "Lote pequeno exige maxItems >= " + MIN_SMALL_BATCH + " (recebido " + maxItems
                            + "). Para um único Q&A use indexSinglePublishedQa.",
                    "Corrigir maxItems para um valor entre " + MIN_SMALL_BATCH + " e "
                            + MAX_SMALL_BATCH + ".");
        }
        if (maxItems > MAX_SMALL_BATCH) {
            return emptyResult(BATCH_MODE, batchId, indexedAt, indexedBy, maxItems, MAX_SMALL_BATCH,
                    "Lote pequeno não pode exceder " + MAX_SMALL_BATCH + " itens (recebido "
                            + maxItems + "). Indexar vários não é escalar livremente.",
                    "Reduzir maxItems para <= " + MAX_SMALL_BATCH
                            + "; lotes maiores ficam para E9C.");
        }

        if (publicationResult == null) {
            return emptyResult(BATCH_MODE, null, indexedAt, indexedBy, maxItems, maxItems,
                    "Resultado de publicação ausente.",
                    "Fornecer um AtFaqGovernedPublicationExecutionResult válido.");
        }
        if (organization == null || organization.getId() == null) {
            return emptyResult(BATCH_MODE, batchId, indexedAt, indexedBy, maxItems, maxItems,
                    "Organização ausente.",
                    "Fornecer uma organização válida (nunca a base real do piloto).");
        }

        UUID organizationId = organization.getId();
        List<AtFaqGovernedPublicationExecutionItemResult> published = publishedItems(publicationResult);
        int totalPublishedItems = published.size();

        if (published.isEmpty()) {
            return emptyResult(BATCH_MODE, batchId, indexedAt, indexedBy, maxItems, maxItems,
                    "Nenhum item publicado na publicação fornecida — nada a indexar.",
                    "Executar E8B.3 primeiro; E9B indexa um lote pequeno de Q&A já publicados.");
        }

        List<AtFaqRagIndexingItemResult> itemResults = new ArrayList<>();
        int indexed = 0;
        int eligibleForIndexing = 0;
        int blocked = 0;
        int skipped = 0;
        int embeddingRows = 0;
        int ragExpected = 0;
        int deferredByLimit = 0;

        for (AtFaqGovernedPublicationExecutionItemResult item : published) {
            GuardOutcome guard = evaluateGuards(item, organizationId);

            if (!guard.eligible()) {
                itemResults.add(blockedItem(item, guard.qa(), guard.blockingReasons()));
                blocked++;
                continue;
            }

            eligibleForIndexing++;
            if (indexed < maxItems) {
                AtFaqRagIndexingItemResult done = doIndex(item, guard.qa());
                itemResults.add(done);
                if (done.indexed()) {
                    indexed++;
                    embeddingRows += done.embeddingRows();
                    if (done.ragExpectedToRetrieve()) {
                        ragExpected++;
                    }
                } else {
                    // Defensive: indexer ran but left no observable row — count as blocked, never success.
                    blocked++;
                    eligibleForIndexing--;
                }
            } else {
                itemResults.add(deferredByBatchLimit(item, guard.qa(), maxItems));
                skipped++;
                deferredByLimit++;
            }
        }

        AtFaqRagIndexingTotals totals = new AtFaqRagIndexingTotals(
                BATCH_MODE, totalPublishedItems, eligibleForIndexing, indexed, skipped, blocked,
                embeddingRows, ragExpected);

        List<String> globalWarnings = new ArrayList<>();
        if (deferredByLimit > 0) {
            globalWarnings.add("Limite de lote pequeno (maxItems=" + maxItems + "): "
                    + deferredByLimit + " item(s) elegível(eis) diferido(s) para um lote futuro.");
        }

        log.debug("E9B indexou lote — indexed={}, deferredByLimit={}, blocked={}, maxItems={}",
                indexed, deferredByLimit, blocked, maxItems);

        return new AtFaqRagIndexingResult(
                publicationResult.batchId(), indexedAt, indexedBy, BATCH_MODE,
                totals, itemResults, globalWarnings, List.of(), NEXT_ACTIONS, maxItems, maxItems);
    }

    // ------------------------------------------------------------------------------------------

    private List<AtFaqGovernedPublicationExecutionItemResult> publishedItems(
            AtFaqGovernedPublicationExecutionResult publicationResult) {
        return publicationResult.itemResults().stream()
                .filter(AtFaqGovernedPublicationExecutionItemResult::published)
                .toList();
    }

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
                null, // normalizedQuestion resolved from the entity during indexing
                index,
                true,  // singleQaOnly — each command is a decision about exactly one Q&A
                false, // productionDataAllowed
                guardChecks,
                warnings,
                blocking);
    }

    /**
     * Evaluate every indexing guard for one published item <b>without</b> writing anything. Shared by
     * the single and the batch flows so both obey identical guards.
     */
    private GuardOutcome evaluateGuards(
            AtFaqGovernedPublicationExecutionItemResult item, UUID organizationId) {

        AtFaqRagIndexingCommand command = plan(item);
        if (!command.index()) {
            return new GuardOutcome(null, command.blockingReasons());
        }

        UUID qaId = command.knowledgeQaId();
        Optional<KnowledgeQuestionAnswer> found = qaRepository.findById(qaId);
        if (found.isEmpty()) {
            return new GuardOutcome(null, List.of("Q&A não encontrada na BD isolada."));
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

        return new GuardOutcome(qa, blocking);
    }

    /** Run the guards and, if all pass, index the single target Q&A through the real indexer. */
    private AtFaqRagIndexingItemResult indexOne(
            AtFaqGovernedPublicationExecutionItemResult item, UUID organizationId) {
        GuardOutcome guard = evaluateGuards(item, organizationId);
        if (!guard.eligible()) {
            return blockedItem(item, guard.qa(), guard.blockingReasons());
        }
        return doIndex(item, guard.qa());
    }

    /** Effective indexing of an already-guard-approved Q&A through the real indexer contract. */
    private AtFaqRagIndexingItemResult doIndex(
            AtFaqGovernedPublicationExecutionItemResult item, KnowledgeQuestionAnswer qa) {

        UUID qaId = qa.getId();
        String question = firstNonBlank(qa.getNormalizedQuestion(), qa.getOriginalQuestion());
        String topic = qa.getTopic() != null ? qa.getTopic().name() : null;

        // In the isolated test this is the real KnowledgeQaEmbeddingIndexerImpl SQL fed by a
        // deterministic embedding — no external call.
        indexer.index(qaId, question, qa.getTechnicalAnswer(), topic);

        int rows = countEmbeddingRows(qaId);
        boolean embeddingPresent = rows >= 1;
        if (!embeddingPresent) {
            log.warn("Indexação não produziu embedding observável para qaId={}", qaId);
            return new AtFaqRagIndexingItemResult(
                    item.externalId(), qaId, false, false, false, false, 0,
                    qa.getNormalizedQuestion(),
                    List.of("Indexador executou mas não há linha de embedding visível."),
                    List.of("Indexação sem efeito observável na BD isolada."),
                    List.of("Investigar o mecanismo de embedding antes de prosseguir."));
        }

        log.debug("Indexou qaId={} — embeddingRows={}", qaId, rows);
        return new AtFaqRagIndexingItemResult(
                item.externalId(),
                qaId,
                true,  // eligibleForIndexing
                true,  // indexed
                true,  // embeddingPresent
                true,  // ragExpectedToRetrieve
                rows,
                qa.getNormalizedQuestion(),
                List.of(),
                List.of(),
                List.of("Indexado em BD isolada com embedding determinístico de teste. "
                        + "RAG pode agora recuperar este Q&A."));
    }

    private AtFaqRagIndexingItemResult deferredBySinglePolicy(
            AtFaqGovernedPublicationExecutionItemResult item) {
        int rows = item.knowledgeQaId() == null ? 0 : countEmbeddingRows(item.knowledgeQaId());
        return new AtFaqRagIndexingItemResult(
                item.externalId(),
                item.knowledgeQaId(),
                false, // eligibleForIndexing — deferred by the single-Q&A policy, not evaluated here
                false, // indexed
                rows >= 1,
                false, // ragExpectedToRetrieve — this run did not index it
                rows,
                null,
                List.of("Diferido pela política E9A single-Q&A."),
                List.of(),
                List.of("Indexação em lote governado pequeno fica para E9B."));
    }

    private AtFaqRagIndexingItemResult deferredByBatchLimit(
            AtFaqGovernedPublicationExecutionItemResult item, KnowledgeQuestionAnswer qa, int maxItems) {
        int rows = item.knowledgeQaId() == null ? 0 : countEmbeddingRows(item.knowledgeQaId());
        return new AtFaqRagIndexingItemResult(
                item.externalId(),
                item.knowledgeQaId(),
                true,  // eligibleForIndexing — it passed every guard; only the batch limit deferred it
                false, // indexed
                rows >= 1,
                false, // ragExpectedToRetrieve — this run did not index it
                rows,
                qa != null ? qa.getNormalizedQuestion() : null,
                List.of("Elegível, mas diferido pelo limite do lote pequeno (maxItems=" + maxItems + ")."),
                List.of(),
                List.of("Indexar num lote futuro; o limite do lote pequeno foi respeitado."));
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
            AtFaqRagIndexingMode mode, String batchId, Instant indexedAt, String indexedBy,
            int requestedMaxItems, int effectiveMaxItems,
            String blockingError, String nextAction) {
        return new AtFaqRagIndexingResult(
                batchId, indexedAt, indexedBy, mode,
                new AtFaqRagIndexingTotals(mode, 0, 0, 0, 0, 0, 0, 0),
                List.of(), List.of(), List.of(blockingError), List.of(nextAction),
                requestedMaxItems, effectiveMaxItems);
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

    /** Outcome of the (read-only) guard evaluation for one item: the loaded Q&A and any blockers. */
    private record GuardOutcome(KnowledgeQuestionAnswer qa, List<String> blockingReasons) {
        private GuardOutcome {
            blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
        }

        boolean eligible() {
            return qa != null && blockingReasons.isEmpty();
        }
    }
}
