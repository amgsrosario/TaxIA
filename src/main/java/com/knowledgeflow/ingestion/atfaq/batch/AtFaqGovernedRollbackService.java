package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerPublicationService;
import com.knowledgeflow.organizations.entity.Organization;
import java.nio.charset.StandardCharsets;
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
 * Governed AT-FAQ rollback service (Bloco E — E10A/E10B).
 *
 * <p>E10A frase-mestra: "Retirar voz não é apagar conhecimento. É neutralizar a recuperação
 * preservando rasto e motivo." E10B frase-mestra: "Calar uma voz prova o travão. Calar um pequeno
 * coro prova a governação."
 *
 * <p>Given the output of an E9A/E9B governed RAG indexing run, this service rolls back published/
 * indexed Q&amp;A by calling the <em>real</em>
 * {@link KnowledgeQuestionAnswerPublicationService#unpublish}, which removes the embedding
 * (desindexar) and clears {@code publishedAt}/{@code publishedBy} (despublicar) atomically per item,
 * keeps {@code curationStatus == VALIDATED}, and records the existing {@code KNOWLEDGE_QA_UNPUBLISHED}
 * audit event. Two governed flows share the same per-Q&amp;A guards:
 * <ul>
 *   <li>{@link #rollbackSingleIndexedQa} (E10A) rolls back exactly one Q&amp;A and blocks when the
 *       indexing result carries more than one eligible item;</li>
 *   <li>{@link #rollbackSmallIndexedBatch} (E10B) rolls back a small governed batch — more than one,
 *       at most {@link AtFaqRollbackTotals#MAX_SMALL_BATCH} — under a hard batch ceiling. Eligible
 *       items beyond {@code maxItems} are deferred (reported, never dropped); items never indexed are
 *       skipped as {@code skippedNotIndexed}; items already rolled back are skipped as
 *       {@code skippedAlreadyRolledBack} (idempotent). It is not a scheduler and not massive rollback.</li>
 * </ul>
 *
 * <p><b>Atomicity (E10B).</b> Each item's {@code unpublish(...)} is its own transaction inside
 * {@code KnowledgeQuestionAnswerPublicationService}. The batch is therefore atomic <em>per item</em>,
 * not transactional across the whole batch: a guard failure or a refusal on one Q&amp;A blocks only
 * that Q&amp;A and never rolls back the ones already neutralized. This is deliberate — it keeps the
 * governed step additive and avoids wrapping several domain unpublish calls in one outer transaction,
 * which would be a structural change to the publication service. The report distinguishes
 * {@code rolledBack}, {@code skippedAlreadyRolledBack}, {@code skippedNotIndexed}, {@code blocked}
 * and {@code deferredDueToLimit} so a partial batch is always legible.
 *
 * <p>It is meant to run against an isolated Testcontainers database. There, the injected
 * {@code publicationService} is constructed with the real {@code KnowledgeQaEmbeddingIndexerImpl}
 * (never the {@code pgtest} stub), so {@code unpublish(...)} genuinely deletes the row from
 * {@code knowledge_qa_embeddings}; a {@link AtFaqRollbackRagProbe} backed by a real
 * {@code RagSearchService} proves each Q&amp;A is retrieved before and no longer retrieved after —
 * <em>without</em> calling the real embedding model, without any external provider and without ever
 * touching the real pilot base. When no probe is supplied the service refuses to roll back rather
 * than claim an unverified retrieval state.
 *
 * <p>The rollback reason is <b>mandatory</b> for the whole run. The current {@code unpublish(...)}
 * does not persist a motive (see the E10-prep inventory); E10A/E10B therefore carry the reason
 * through the command and the report and document the lack of formal persistence as a gap for
 * E10-policy — they do <b>not</b> add a migration or an audit-schema change in this step.
 *
 * <p>Rollback guards (a Q&amp;A is rolled back only if <b>all</b> hold, identical for single and batch):
 * <ol>
 *   <li>a non-blank reason was supplied for the run;</li>
 *   <li>the item was indexed in E9A/E9B ({@code indexed == true}, {@code embeddingPresent == true})
 *       with a {@code knowledgeQaId};</li>
 *   <li>the Q&amp;A exists and belongs to the supplied organization;</li>
 *   <li>{@code isPublished() == true} with {@code publishedAt}/{@code publishedBy} set;</li>
 *   <li>{@code curationStatus == VALIDATED};</li>
 *   <li>{@code embeddingRowsBefore == 1};</li>
 *   <li>a RAG probe is available and confirms retrieval before the rollback.</li>
 * </ol>
 *
 * <p>Idempotency: a second run over already rolled-back Q&amp;A (no publication, no embedding) is
 * classified as <em>skipped (already rolled back)</em> — detected before {@code unpublish(...)} is
 * called, so the service never lets the underlying {@code INVALID_STATE_TRANSITION} escape, never
 * recreates an embedding and never republishes; {@code rolledBack} does not increase.
 */
@Service
public class AtFaqGovernedRollbackService {

    private static final Logger log = LoggerFactory.getLogger(AtFaqGovernedRollbackService.class);

    private static final AtFaqRollbackMode SINGLE_MODE = AtFaqRollbackMode.TEST_ISOLATED_SINGLE_QA;
    private static final AtFaqRollbackMode BATCH_MODE = AtFaqRollbackMode.SMALL_BATCH_TEST_ISOLATED;

    /** Smallest batch that is still a batch: one Q&A uses {@link #rollbackSingleIndexedQa}. */
    private static final int MIN_SMALL_BATCH = 2;
    /** Hard ceiling on a governed small batch. */
    private static final int MAX_SMALL_BATCH = AtFaqRollbackTotals.MAX_SMALL_BATCH;

    private static final List<String> NEXT_ACTIONS = List.of(
            "E10-policy: persistência formal do motivo e auditoria dedicada de rollback.",
            "E9C: lote piloto real apenas depois de rollback e política consolidados.");

    private final KnowledgeQuestionAnswerPublicationService publicationService;
    private final KnowledgeQuestionAnswerRepository qaRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AtFaqRollbackRagProbe ragProbe;
    private final Clock clock;

    @Autowired
    public AtFaqGovernedRollbackService(
            KnowledgeQuestionAnswerPublicationService publicationService,
            KnowledgeQuestionAnswerRepository qaRepository,
            JdbcTemplate jdbcTemplate) {
        this(publicationService, qaRepository, jdbcTemplate, null, Clock.systemUTC());
    }

    /**
     * Full-control constructor used by the isolated integration test: supplies the RAG probe (so the
     * before/after retrieval state can be proven) and a fixed {@link Clock}.
     */
    public AtFaqGovernedRollbackService(
            KnowledgeQuestionAnswerPublicationService publicationService,
            KnowledgeQuestionAnswerRepository qaRepository,
            JdbcTemplate jdbcTemplate,
            AtFaqRollbackRagProbe ragProbe,
            Clock clock) {
        this.publicationService = publicationService;
        this.qaRepository = qaRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.ragProbe = ragProbe;
        this.clock = clock;
    }

    /**
     * Roll back exactly one published/indexed Q&amp;A from an E9A/E9B indexing run, in an isolated DB
     * (E10A).
     *
     * @param indexingResult output of the E9A/E9B governed RAG indexing
     * @param organization   the owning organization (never the real pilot base outside tests)
     * @param rolledBackBy   actor recorded as the rollback requester
     * @param reason         mandatory, non-blank motive for the rollback
     */
    public AtFaqRollbackResult rollbackSingleIndexedQa(
            AtFaqRagIndexingResult indexingResult,
            Organization organization,
            String rolledBackBy,
            String reason) {

        Instant rolledBackAt = clock.instant();
        String batchId = indexingResult != null ? indexingResult.batchId() : null;

        if (reason == null || reason.isBlank()) {
            return emptyResult(SINGLE_MODE, batchId, rolledBackAt, rolledBackBy, 0, 1, 1,
                    "Motivo de rollback ausente — o motivo é obrigatório em E10A.",
                    "Fornecer um motivo não vazio para o rollback governado.");
        }
        if (indexingResult == null) {
            return emptyResult(SINGLE_MODE, null, rolledBackAt, rolledBackBy, 0, 1, 1,
                    "Resultado de indexação ausente.",
                    "Fornecer um AtFaqRagIndexingResult válido (saída de E9A/E9B).");
        }
        if (organization == null || organization.getId() == null) {
            return emptyResult(SINGLE_MODE, batchId, rolledBackAt, rolledBackBy, 0, 1, 1,
                    "Organização ausente.",
                    "Fornecer uma organização válida (nunca a base real do piloto).");
        }

        UUID organizationId = organization.getId();

        // Single-Q&A selection: only items actually indexed in E9A/E9B are rollback candidates.
        List<AtFaqRagIndexingItemResult> indexed = indexingResult.itemResults().stream()
                .filter(i -> i.indexed() && i.embeddingPresent() && i.knowledgeQaId() != null)
                .toList();
        int totalIndexedItems = indexed.size();

        if (indexed.isEmpty()) {
            return emptyResult(SINGLE_MODE, batchId, rolledBackAt, rolledBackBy, 0, 1, 1,
                    "Nenhum item indexado na indexação fornecida — nada a reverter.",
                    "Executar E9A/E9B primeiro; E10A reverte um único Q&A já indexado.");
        }
        if (indexed.size() > 1) {
            // More than one eligible item in single mode is a hard block: never guess which to revert.
            AtFaqRollbackTotals totals = new AtFaqRollbackTotals(
                    totalIndexedItems, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0);
            return new AtFaqRollbackResult(
                    batchId, rolledBackAt, rolledBackBy, SINGLE_MODE, totals, List.of(),
                    List.of(),
                    List.of("Mais de um item indexado elegível (" + indexed.size()
                            + ") em modo single-Q&A. Rollback de lote é E10B."),
                    NEXT_ACTIONS, 1, 1);
        }

        AtFaqRagIndexingItemResult target = indexed.get(0);
        UUID actingUserId = deterministicActor(rolledBackBy);
        AtFaqRollbackItemResult itemResult =
                rollbackOne(target, organizationId, actingUserId, reason, 1);

        AtFaqRollbackTotals totals = singleTotalsFor(totalIndexedItems, itemResult);

        List<String> globalWarnings = new ArrayList<>();
        globalWarnings.add(motivePersistenceWarning());

        return new AtFaqRollbackResult(
                batchId, rolledBackAt, rolledBackBy, SINGLE_MODE, totals,
                List.of(itemResult), globalWarnings, List.of(), NEXT_ACTIONS, 1, 1);
    }

    /**
     * Roll back a small governed batch of published/indexed Q&amp;A from an E9A/E9B indexing run, in an
     * isolated DB (E10B). Every per-Q&amp;A guard from {@link #rollbackSingleIndexedQa} applies
     * unchanged; the only addition is a hard batch ceiling. Eligible items beyond {@code maxItems} are
     * deferred (reported, never dropped); items never indexed are skipped; items already rolled back
     * are skipped idempotently.
     *
     * @param indexingResult output of the E9A/E9B governed RAG indexing
     * @param organization   the owning organization (never the real pilot base outside tests)
     * @param rolledBackBy   actor recorded as the rollback requester
     * @param reason         mandatory, non-blank motive for the whole batch
     * @param maxItems       batch limit; must be in [{@value #MIN_SMALL_BATCH}, {@value #MAX_SMALL_BATCH}]
     *                       — 1 belongs to the single flow, &gt;3 is refused (never massive rollback)
     */
    public AtFaqRollbackResult rollbackSmallIndexedBatch(
            AtFaqRagIndexingResult indexingResult,
            Organization organization,
            String rolledBackBy,
            String reason,
            int maxItems) {

        Instant rolledBackAt = clock.instant();
        String batchId = indexingResult != null ? indexingResult.batchId() : null;

        // --- Controlled-configuration guards: refuse to revert anything on an invalid batch limit ---
        if (maxItems < MIN_SMALL_BATCH) {
            return emptyResult(BATCH_MODE, batchId, rolledBackAt, rolledBackBy, 0, maxItems, 0,
                    "Rollback de lote pequeno exige maxItems >= " + MIN_SMALL_BATCH + " (recebido "
                            + maxItems + "). Para um único Q&A use rollbackSingleIndexedQa.",
                    "Corrigir maxItems para um valor entre " + MIN_SMALL_BATCH + " e "
                            + MAX_SMALL_BATCH + ".");
        }
        if (maxItems > MAX_SMALL_BATCH) {
            return emptyResult(BATCH_MODE, batchId, rolledBackAt, rolledBackBy, 0, maxItems,
                    MAX_SMALL_BATCH,
                    "Rollback de lote pequeno não pode exceder " + MAX_SMALL_BATCH + " itens (recebido "
                            + maxItems + "). Calar um pequeno coro não é calar em massa.",
                    "Reduzir maxItems para <= " + MAX_SMALL_BATCH
                            + "; rollback massivo fica fora de âmbito.");
        }

        if (reason == null || reason.isBlank()) {
            return emptyResult(BATCH_MODE, batchId, rolledBackAt, rolledBackBy, 0, maxItems, maxItems,
                    "Motivo de rollback ausente — o motivo é obrigatório para o lote em E10B.",
                    "Fornecer um motivo não vazio para o rollback governado do lote.");
        }
        if (indexingResult == null) {
            return emptyResult(BATCH_MODE, null, rolledBackAt, rolledBackBy, 0, maxItems, maxItems,
                    "Resultado de indexação ausente.",
                    "Fornecer um AtFaqRagIndexingResult válido (saída de E9A/E9B).");
        }
        if (organization == null || organization.getId() == null) {
            return emptyResult(BATCH_MODE, batchId, rolledBackAt, rolledBackBy, 0, maxItems, maxItems,
                    "Organização ausente.",
                    "Fornecer uma organização válida (nunca a base real do piloto).");
        }

        UUID organizationId = organization.getId();
        UUID actingUserId = deterministicActor(rolledBackBy);
        List<AtFaqRagIndexingItemResult> items = indexingResult.itemResults();

        if (items.isEmpty()) {
            return emptyResult(BATCH_MODE, batchId, rolledBackAt, rolledBackBy, 0, maxItems, maxItems,
                    "Indexação fornecida sem itens — nada a reverter.",
                    "Executar E9A/E9B primeiro; E10B reverte um lote pequeno de Q&A já indexados.");
        }

        List<AtFaqRollbackItemResult> itemResults = new ArrayList<>();
        int indexedCandidates = 0;
        int rolledBack = 0;
        int eligible = 0;
        int blocked = 0;
        int skipped = 0;
        int skippedAlreadyRolledBack = 0;
        int skippedNotIndexed = 0;
        int deferredDueToLimit = 0;
        int embeddingRowsBefore = 0;
        int embeddingRowsAfter = 0;
        int ragRecoveredBefore = 0;
        int ragRecoveredAfter = 0;
        int position = 0;

        for (AtFaqRagIndexingItemResult item : items) {
            position++;
            boolean candidate =
                    item.indexed() && item.embeddingPresent() && item.knowledgeQaId() != null;

            if (!candidate) {
                itemResults.add(skippedNotIndexedItem(item, reason, position));
                skippedNotIndexed++;
                skipped++;
                continue;
            }

            indexedCandidates++;
            if (rolledBack < maxItems) {
                AtFaqRollbackItemResult r =
                        rollbackOne(item, organizationId, actingUserId, reason, position);
                itemResults.add(r);
                if (r.rolledBack()) {
                    rolledBack++;
                    eligible++;
                    embeddingRowsBefore += r.embeddingRowsBefore();
                    embeddingRowsAfter += r.embeddingRowsAfter();
                    if (r.ragRecoveredBefore()) {
                        ragRecoveredBefore++;
                    }
                    if (r.ragRecoveredAfter()) {
                        ragRecoveredAfter++;
                    }
                } else if (r.alreadyRolledBack()) {
                    skippedAlreadyRolledBack++;
                    skipped++;
                } else {
                    blocked++;
                }
            } else {
                itemResults.add(deferredByBatchLimit(item, reason, maxItems, position));
                deferredDueToLimit++;
                eligible++;
                skipped++;
            }
        }

        AtFaqRollbackTotals totals = new AtFaqRollbackTotals(
                indexedCandidates,
                eligible,
                rolledBack,
                rolledBack, // unpublished matches rolledBack
                rolledBack, // deindexed matches rolledBack
                embeddingRowsBefore,
                embeddingRowsAfter,
                ragRecoveredBefore,
                ragRecoveredAfter,
                skipped,
                blocked,
                maxItems,
                maxItems,
                deferredDueToLimit,
                skippedAlreadyRolledBack,
                skippedNotIndexed,
                rolledBack); // batchSize = coro effectively rolled back

        List<String> globalWarnings = new ArrayList<>();
        globalWarnings.add(motivePersistenceWarning());
        globalWarnings.add("Atomicidade por item, não transacional ao lote: uma falha num Q&A "
                + "bloqueia apenas esse item e não reverte os já neutralizados.");
        if (deferredDueToLimit > 0) {
            globalWarnings.add("Limite de lote pequeno (maxItems=" + maxItems + "): "
                    + deferredDueToLimit + " item(s) elegível(eis) diferido(s) para um lote futuro.");
        }

        log.debug("E10B reverteu lote — rolledBack={}, deferredByLimit={}, alreadyRolledBack={}, "
                        + "notIndexed={}, blocked={}, maxItems={}",
                rolledBack, deferredDueToLimit, skippedAlreadyRolledBack, skippedNotIndexed, blocked,
                maxItems);

        return new AtFaqRollbackResult(
                batchId, rolledBackAt, rolledBackBy, BATCH_MODE, totals,
                itemResults, globalWarnings, List.of(), NEXT_ACTIONS, maxItems, maxItems);
    }

    // ------------------------------------------------------------------------------------------

    private AtFaqRollbackItemResult rollbackOne(
            AtFaqRagIndexingItemResult target, UUID organizationId, UUID actingUserId, String reason,
            int batchPosition) {

        String externalId = target.externalId();
        UUID qaId = target.knowledgeQaId();

        Optional<KnowledgeQuestionAnswer> found = qaRepository.findById(qaId);
        if (found.isEmpty()) {
            return blocked(externalId, qaId, reason, 0, false, batchPosition,
                    List.of("Q&A não encontrada na BD isolada."));
        }
        KnowledgeQuestionAnswer qa = found.get();

        if (qa.getOrganization() == null || !organizationId.equals(qa.getOrganization().getId())) {
            return blocked(externalId, qaId, reason, countEmbeddingRows(qaId), qa.isPublished(),
                    batchPosition, List.of("Q&A não pertence à organização fornecida."));
        }

        int embeddingRowsBefore = countEmbeddingRows(qaId);
        boolean publishedBefore = qa.isPublished();

        // Idempotency: already rolled back (not published and no embedding) → skipped, not an error.
        if (!publishedBefore && embeddingRowsBefore == 0) {
            return alreadyRolledBack(externalId, qaId, reason, batchPosition);
        }
        // Inconsistent residue: unpublished but an embedding still exists → block, never guess.
        if (!publishedBefore && embeddingRowsBefore > 0) {
            return blocked(externalId, qaId, reason, embeddingRowsBefore, false, batchPosition,
                    List.of("Q&A não publicada mas ainda com embedding — estado inconsistente."));
        }

        // --- published item: evaluate the remaining guards before any destructive call ---
        List<String> blocking = new ArrayList<>();
        if (qa.getPublishedAt() == null || qa.getPublishedBy() == null) {
            blocking.add("Publicada sem publishedAt/publishedBy consistentes.");
        }
        if (qa.getCurationStatus() != KnowledgeCurationStatus.VALIDATED) {
            blocking.add("Estado de curadoria != VALIDATED: " + qa.getCurationStatus() + ".");
        }
        if (embeddingRowsBefore != 1) {
            blocking.add("embeddingRowsBefore != 1 (got " + embeddingRowsBefore + ").");
        }
        if (ragProbe == null) {
            blocking.add("Sem verificador de RAG — o rollback não reverte sem provar o antes/depois.");
        }
        if (!blocking.isEmpty()) {
            return blocked(externalId, qaId, reason, embeddingRowsBefore, publishedBefore,
                    batchPosition, blocking);
        }

        boolean ragRecoveredBefore = ragProbe.recovers(organizationId, qaId);
        if (!ragRecoveredBefore) {
            return blocked(externalId, qaId, reason, embeddingRowsBefore, publishedBefore,
                    batchPosition,
                    List.of("Embedding presente mas RAG não recupera antes — estado inconsistente."));
        }

        // --- effective rollback through the real publication service (real indexer in IT) ---
        try {
            publicationService.unpublish(organizationId, actingUserId, qaId);
        } catch (BusinessException e) {
            log.warn("Rollback recusado pelo serviço para {}: {}", externalId, e.getMessage());
            return blocked(externalId, qaId, reason, embeddingRowsBefore, publishedBefore,
                    batchPosition, List.of("Despublicação recusada pelo serviço: " + e.getMessage()));
        }

        KnowledgeQuestionAnswer after = qaRepository.findById(qaId).orElse(null);
        boolean publishedAfter = after != null && after.isPublished();
        int embeddingRowsAfter = countEmbeddingRows(qaId);
        boolean ragRecoveredAfter = ragProbe.recovers(organizationId, qaId);

        // Defensive: only claim a rollback when the after-state genuinely proves neutralization.
        if (publishedAfter || embeddingRowsAfter != 0 || ragRecoveredAfter) {
            List<String> why = new ArrayList<>();
            if (publishedAfter) why.add("continua publicada após unpublish.");
            if (embeddingRowsAfter != 0) why.add("embedding ainda presente (" + embeddingRowsAfter + ").");
            if (ragRecoveredAfter) why.add("RAG ainda recupera após rollback.");
            log.error("Rollback de {} não neutralizou a recuperação: {}", externalId, why);
            return new AtFaqRollbackItemResult(
                    externalId, qaId, true, false, false, !publishedAfter, embeddingRowsAfter == 0,
                    embeddingRowsAfter == 0, ragRecoveredBefore, ragRecoveredAfter,
                    embeddingRowsBefore, embeddingRowsAfter, publishedBefore, publishedAfter, reason,
                    List.of(), List.copyOf(why),
                    List.of("Investigar por que a neutralização não foi observável."),
                    false, false, false, batchPosition);
        }

        log.info("Rollback governado de qaId={} — unpublish real + embedding removido (motivo presente)",
                qaId);
        return new AtFaqRollbackItemResult(
                externalId,
                qaId,
                true,  // eligibleForRollback
                true,  // rolledBack
                false, // alreadyRolledBack
                true,  // unpublished
                true,  // deindexed
                true,  // embeddingRemoved
                true,  // ragRecoveredBefore
                false, // ragRecoveredAfter
                embeddingRowsBefore,
                embeddingRowsAfter,
                publishedBefore,
                publishedAfter,
                reason,
                List.of(),
                List.of(),
                List.of("Voz neutralizada: despublicado e desindexado em BD isolada. "
                        + "Conhecimento e auditoria preservados."),
                false, // skippedAlreadyRolledBack
                false, // skippedNotIndexed
                false, // deferredDueToLimit
                batchPosition);
    }

    private AtFaqRollbackItemResult alreadyRolledBack(
            String externalId, UUID qaId, String reason, int batchPosition) {
        return new AtFaqRollbackItemResult(
                externalId, qaId,
                false, // eligibleForRollback — nothing left to do
                false, // rolledBack (not in THIS run)
                true,  // alreadyRolledBack
                false, false, false,
                false, // ragRecoveredBefore — already gone
                false, // ragRecoveredAfter
                0, 0,
                false, // publishedBefore
                false, // publishedAfter
                reason,
                List.of("Já revertido num run anterior; reutilização idempotente."),
                List.of(),
                List.of("Nada a fazer: rollback idempotente. Sem nova indexação, sem republicação."),
                true,  // skippedAlreadyRolledBack
                false, // skippedNotIndexed
                false, // deferredDueToLimit
                batchPosition);
    }

    private AtFaqRollbackItemResult blocked(
            String externalId, UUID qaId, String reason,
            int embeddingRowsBefore, boolean publishedBefore, int batchPosition,
            List<String> blocking) {
        return new AtFaqRollbackItemResult(
                externalId, qaId,
                false, false, false, false, false, false,
                false, false,
                embeddingRowsBefore, embeddingRowsBefore,
                publishedBefore, publishedBefore,
                reason,
                List.of(),
                List.copyOf(blocking),
                List.of("Corrigir a causa do bloqueio antes de reverter. Rollback recusado."),
                false, false, false, batchPosition);
    }

    /** Batch-only: an item the indexing result never indexed — nothing to revert, only reported. */
    private AtFaqRollbackItemResult skippedNotIndexedItem(
            AtFaqRagIndexingItemResult item, String reason, int batchPosition) {
        return new AtFaqRollbackItemResult(
                item.externalId(), item.knowledgeQaId(),
                false, false, false, false, false, false,
                false, false,
                0, 0,
                false, false,
                reason,
                List.of("Não indexado na indexação fornecida — nada a reverter."),
                List.of(),
                List.of("Indexar via E9A/E9B antes de qualquer rollback."),
                false, // skippedAlreadyRolledBack
                true,  // skippedNotIndexed
                false, // deferredDueToLimit
                batchPosition);
    }

    /** Batch-only: an eligible candidate deferred because the batch limit was already reached. */
    private AtFaqRollbackItemResult deferredByBatchLimit(
            AtFaqRagIndexingItemResult item, String reason, int maxItems, int batchPosition) {
        int rows = item.knowledgeQaId() == null ? 0 : countEmbeddingRows(item.knowledgeQaId());
        return new AtFaqRollbackItemResult(
                item.externalId(), item.knowledgeQaId(),
                true,  // eligibleForRollback — passed the indexed gate; only the batch limit deferred it
                false, // rolledBack
                false, // alreadyRolledBack
                false, false, false,
                false, false,
                rows, rows,
                true, true, // untouched: still published, still indexed
                reason,
                List.of("Elegível, mas diferido pelo limite do lote pequeno (maxItems=" + maxItems + ")."),
                List.of(),
                List.of("Reverter num lote futuro; o limite do lote pequeno foi respeitado."),
                false, // skippedAlreadyRolledBack
                false, // skippedNotIndexed
                true,  // deferredDueToLimit
                batchPosition);
    }

    private AtFaqRollbackTotals singleTotalsFor(int totalIndexedItems, AtFaqRollbackItemResult item) {
        int rolledBack = item.rolledBack() ? 1 : 0;
        int eligible = item.eligibleForRollback() ? 1 : 0;
        int skippedAlready = item.alreadyRolledBack() ? 1 : 0;
        int skipped = skippedAlready;
        int blocked = (!item.rolledBack() && !item.alreadyRolledBack()) ? 1 : 0;
        return new AtFaqRollbackTotals(
                totalIndexedItems,
                eligible,
                rolledBack,
                rolledBack, // unpublished matches rolledBack in single mode
                rolledBack, // deindexed matches rolledBack in single mode
                item.embeddingRowsBefore(),
                item.embeddingRowsAfter(),
                item.ragRecoveredBefore() ? 1 : 0,
                item.ragRecoveredAfter() ? 1 : 0,
                skipped,
                blocked,
                1, // requestedMaxItems
                1, // effectiveMaxItems
                0, // deferredDueToLimit
                skippedAlready,
                0, // skippedNotIndexed
                rolledBack); // batchSize
    }

    private AtFaqRollbackResult emptyResult(
            AtFaqRollbackMode mode, String batchId, Instant rolledBackAt, String rolledBackBy,
            int totalIndexedItems, int requestedMaxItems, int effectiveMaxItems,
            String blockingError, String nextAction) {
        AtFaqRollbackTotals totals = new AtFaqRollbackTotals(
                totalIndexedItems, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                requestedMaxItems, effectiveMaxItems, 0, 0, 0, 0);
        return new AtFaqRollbackResult(
                batchId, rolledBackAt, rolledBackBy, mode, totals,
                List.of(), List.of(), List.of(blockingError), List.of(nextAction),
                requestedMaxItems, effectiveMaxItems);
    }

    private static String motivePersistenceWarning() {
        return "Motivo de rollback não é persistido formalmente (unpublish não guarda motivo). "
                + "Lacuna documentada para E10-policy.";
    }

    private int countEmbeddingRows(UUID qaId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                Integer.class, qaId.toString());
        return count == null ? 0 : count;
    }

    /**
     * Deterministic id of the rollback "acting user" for audit purposes. Derived from the requester
     * name so the same actor id is reused across runs (idempotent audit) and can be provisioned as a
     * service-account user in the isolated test database. Package-private so the IT can insert a
     * matching {@code users} row and satisfy the audit → users FK.
     */
    static UUID deterministicActor(String rolledBackBy) {
        String seed = "taxia-governed-rollback:" + (rolledBackBy == null ? "" : rolledBackBy);
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
