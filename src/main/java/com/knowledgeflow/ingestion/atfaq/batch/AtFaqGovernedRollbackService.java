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
 * Governed AT-FAQ rollback service (Bloco E — E10A).
 *
 * <p>Frase-mestra: "Retirar voz não é apagar conhecimento. É neutralizar a recuperação preservando
 * rasto e motivo."
 *
 * <p>Given the output of an E9A/E9B governed RAG indexing run, this service rolls back exactly
 * <b>one</b> published/indexed Q&amp;A by calling the <em>real</em>
 * {@link KnowledgeQuestionAnswerPublicationService#unpublish}, which removes the embedding
 * (desindexar) and clears {@code publishedAt}/{@code publishedBy} (despublicar) atomically, keeps
 * {@code curationStatus == VALIDATED}, and records the existing {@code KNOWLEDGE_QA_UNPUBLISHED}
 * audit event. It is not a scheduler, not a batch and not an endpoint: E10B (small batch) and
 * E10-policy (formal motive/audit) are separate, later steps.
 *
 * <p>It is meant to run against an isolated Testcontainers database. There, the injected
 * {@code publicationService} is constructed with the real {@code KnowledgeQaEmbeddingIndexerImpl}
 * (never the {@code pgtest} stub), so {@code unpublish(...)} genuinely deletes the row from
 * {@code knowledge_qa_embeddings}; a {@link AtFaqRollbackRagProbe} backed by a real
 * {@code RagSearchService} proves the Q&amp;A is retrieved before and no longer retrieved after —
 * <em>without</em> calling the real embedding model, without any external provider and without ever
 * touching the real pilot base. When no probe is supplied the service refuses to roll back rather
 * than claim an unverified retrieval state.
 *
 * <p>The rollback reason is <b>mandatory</b>. The current {@code unpublish(...)} does not persist a
 * motive (see the E10-prep inventory); E10A therefore carries the reason through the command and the
 * report and documents the lack of formal persistence as a gap for E10B/E10-policy — it does
 * <b>not</b> add a migration or an audit-schema change in this step.
 *
 * <p>Rollback guards (a Q&amp;A is rolled back only if <b>all</b> hold):
 * <ol>
 *   <li>a non-blank reason was supplied;</li>
 *   <li>the item was indexed in E9A/E9B ({@code indexed == true}, {@code embeddingPresent == true})
 *       with a {@code knowledgeQaId};</li>
 *   <li>exactly one such item exists in the indexing result (single-Q&amp;A mode);</li>
 *   <li>the Q&amp;A exists and belongs to the supplied organization;</li>
 *   <li>{@code isPublished() == true} with {@code publishedAt}/{@code publishedBy} set;</li>
 *   <li>{@code curationStatus == VALIDATED};</li>
 *   <li>{@code embeddingRowsBefore == 1};</li>
 *   <li>a RAG probe is available and confirms retrieval before the rollback.</li>
 * </ol>
 *
 * <p>Idempotency: a second run over an already rolled-back Q&amp;A (no publication, no embedding) is
 * classified as <em>skipped (already rolled back)</em> — it is detected before {@code unpublish(...)}
 * is called, so the service never lets the underlying {@code INVALID_STATE_TRANSITION} escape, never
 * recreates an embedding and never republishes.
 */
@Service
public class AtFaqGovernedRollbackService {

    private static final Logger log = LoggerFactory.getLogger(AtFaqGovernedRollbackService.class);

    private static final AtFaqRollbackMode MODE = AtFaqRollbackMode.TEST_ISOLATED_SINGLE_QA;

    private static final List<String> NEXT_ACTIONS = List.of(
            "E10B: rollback governado de um lote pequeno sob os mesmos guardas.",
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
     * Roll back exactly one published/indexed Q&amp;A from an E9A/E9B indexing run, in an isolated DB.
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
            return emptyResult(batchId, rolledBackAt, rolledBackBy, 0,
                    "Motivo de rollback ausente — o motivo é obrigatório em E10A.",
                    "Fornecer um motivo não vazio para o rollback governado.");
        }
        if (indexingResult == null) {
            return emptyResult(null, rolledBackAt, rolledBackBy, 0,
                    "Resultado de indexação ausente.",
                    "Fornecer um AtFaqRagIndexingResult válido (saída de E9A/E9B).");
        }
        if (organization == null || organization.getId() == null) {
            return emptyResult(batchId, rolledBackAt, rolledBackBy, 0,
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
            return emptyResult(batchId, rolledBackAt, rolledBackBy, 0,
                    "Nenhum item indexado na indexação fornecida — nada a reverter.",
                    "Executar E9A/E9B primeiro; E10A reverte um único Q&A já indexado.");
        }
        if (indexed.size() > 1) {
            // More than one eligible item in single mode is a hard block: never guess which to revert.
            AtFaqRollbackTotals totals = new AtFaqRollbackTotals(
                    totalIndexedItems, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            return new AtFaqRollbackResult(
                    batchId, rolledBackAt, rolledBackBy, MODE, totals, List.of(),
                    List.of(),
                    List.of("Mais de um item indexado elegível (" + indexed.size()
                            + ") em modo single-Q&A. Rollback de lote é E10B."),
                    NEXT_ACTIONS);
        }

        AtFaqRagIndexingItemResult target = indexed.get(0);
        UUID actingUserId = deterministicActor(rolledBackBy);
        AtFaqRollbackItemResult itemResult =
                rollbackOne(target, organizationId, actingUserId, reason);

        AtFaqRollbackTotals totals = totalsFor(totalIndexedItems, itemResult);

        List<String> globalWarnings = new ArrayList<>();
        globalWarnings.add("Motivo de rollback não é persistido formalmente (unpublish não guarda "
                + "motivo). Lacuna documentada para E10B/E10-policy.");

        return new AtFaqRollbackResult(
                batchId, rolledBackAt, rolledBackBy, MODE, totals,
                List.of(itemResult), globalWarnings, List.of(), NEXT_ACTIONS);
    }

    // ------------------------------------------------------------------------------------------

    private AtFaqRollbackItemResult rollbackOne(
            AtFaqRagIndexingItemResult target, UUID organizationId, UUID actingUserId, String reason) {

        String externalId = target.externalId();
        UUID qaId = target.knowledgeQaId();

        Optional<KnowledgeQuestionAnswer> found = qaRepository.findById(qaId);
        if (found.isEmpty()) {
            return blocked(externalId, qaId, reason, 0, false,
                    List.of("Q&A não encontrada na BD isolada."));
        }
        KnowledgeQuestionAnswer qa = found.get();

        if (qa.getOrganization() == null || !organizationId.equals(qa.getOrganization().getId())) {
            return blocked(externalId, qaId, reason, countEmbeddingRows(qaId), qa.isPublished(),
                    List.of("Q&A não pertence à organização fornecida."));
        }

        int embeddingRowsBefore = countEmbeddingRows(qaId);
        boolean publishedBefore = qa.isPublished();

        // Idempotency: already rolled back (not published and no embedding) → skipped, not an error.
        if (!publishedBefore && embeddingRowsBefore == 0) {
            return alreadyRolledBack(externalId, qaId, reason);
        }
        // Inconsistent residue: unpublished but an embedding still exists → block, never guess.
        if (!publishedBefore && embeddingRowsBefore > 0) {
            return blocked(externalId, qaId, reason, embeddingRowsBefore, false,
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
            blocking.add("Sem verificador de RAG — E10A não reverte sem provar o antes/depois.");
        }
        if (!blocking.isEmpty()) {
            return blocked(externalId, qaId, reason, embeddingRowsBefore, publishedBefore, blocking);
        }

        boolean ragRecoveredBefore = ragProbe.recovers(organizationId, qaId);
        if (!ragRecoveredBefore) {
            return blocked(externalId, qaId, reason, embeddingRowsBefore, publishedBefore,
                    List.of("Embedding presente mas RAG não recupera antes — estado inconsistente."));
        }

        // --- effective rollback through the real publication service (real indexer in IT) ---
        try {
            publicationService.unpublish(organizationId, actingUserId, qaId);
        } catch (BusinessException e) {
            log.warn("Rollback recusado pelo serviço para {}: {}", externalId, e.getMessage());
            return blocked(externalId, qaId, reason, embeddingRowsBefore, publishedBefore,
                    List.of("Despublicação recusada pelo serviço: " + e.getMessage()));
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
                    List.of("Investigar por que a neutralização não foi observável."));
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
                        + "Conhecimento e auditoria preservados."));
    }

    private AtFaqRollbackItemResult alreadyRolledBack(String externalId, UUID qaId, String reason) {
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
                List.of("Nada a fazer: rollback idempotente. Sem nova indexação, sem republicação."));
    }

    private AtFaqRollbackItemResult blocked(
            String externalId, UUID qaId, String reason,
            int embeddingRowsBefore, boolean publishedBefore, List<String> blocking) {
        return new AtFaqRollbackItemResult(
                externalId, qaId,
                false, false, false, false, false, false,
                false, false,
                embeddingRowsBefore, embeddingRowsBefore,
                publishedBefore, publishedBefore,
                reason,
                List.of(),
                List.copyOf(blocking),
                List.of("Corrigir a causa do bloqueio antes de reverter. Rollback recusado."));
    }

    private AtFaqRollbackTotals totalsFor(int totalIndexedItems, AtFaqRollbackItemResult item) {
        int rolledBack = item.rolledBack() ? 1 : 0;
        int eligible = item.eligibleForRollback() ? 1 : 0;
        int skipped = item.alreadyRolledBack() ? 1 : 0;
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
                blocked);
    }

    private AtFaqRollbackResult emptyResult(
            String batchId, Instant rolledBackAt, String rolledBackBy, int totalIndexedItems,
            String blockingError, String nextAction) {
        return new AtFaqRollbackResult(
                batchId, rolledBackAt, rolledBackBy, MODE,
                new AtFaqRollbackTotals(totalIndexedItems, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of(), List.of(), List.of(blockingError), List.of(nextAction));
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
