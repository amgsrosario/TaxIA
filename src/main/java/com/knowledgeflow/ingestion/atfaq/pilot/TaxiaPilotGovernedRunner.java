package com.knowledgeflow.ingestion.atfaq.pilot;

import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.config.pilot.PilotDatasourceGuard;
import com.knowledgeflow.ingestion.atfaq.AtFaqProperties;
import com.knowledgeflow.ingestion.atfaq.batch.AtFaqRollbackMotive;
import com.knowledgeflow.ingestion.atfaq.batch.GovernedPilotServiceActors;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerPublicationService;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Guarded one-shot governed runner for the future E9C real N=1 — Bloco E, E9C-pilot-runner
 * (PROMPT 93).
 *
 * <p>Frase-mestra: "O primeiro caso real entra por um gesto deliberado, único e reversível — nunca
 * por arranque, agendador ou lote."
 *
 * <p><b>Deliberately narrow.</b> This bean is an inert {@link Service}: it has no
 * {@code @PostConstruct}, implements no {@link org.springframework.boot.CommandLineRunner} /
 * {@link org.springframework.boot.ApplicationRunner}, and registers no scheduler, endpoint, UI or
 * batch. Merely being in the context does nothing. It acts only when one of its three methods is
 * called explicitly — today by an isolated test, in the future by
 * {@link TaxiaPilotGovernedRunnerLauncher} run by hand.
 *
 * <p><b>Three actions, one target, at most one write.</b>
 * <ul>
 *   <li>{@link #status(String)} — read-only preflight; may run even with the E9C flag off.</li>
 *   <li>{@link #publishOne(String)} — publishes exactly one explicit Q&A; requires the flag.</li>
 *   <li>{@link #rollbackOne(String, AtFaqRollbackMotive)} — rolls back exactly one explicit Q&A
 *       with a mandatory motive; requires the flag.</li>
 * </ul>
 * Each call resolves a single target by its explicit {@code externalKey} (enforcing an
 * exactly-one match — 0 or &gt;1 is refused), performs at most one governed write, and returns.
 * There is no batch, no "next", and no auto-discovery.
 *
 * <p><b>Fail-closed, no stack traces as interface.</b> Before any write it re-runs the pilot base
 * check ({@link PilotDatasourceGuard#validate(DataSource)} — the datasource must be
 * {@code knowledgeflow_pilot} on a loopback host) and, for writes, requires
 * {@code knowledgeflow.ingestion.at-faq.e9c-pilot-enabled} ({@code AT_FAQ_E9C_PILOT_ENABLED}) to be
 * true. Every refusal is a normal {@link PilotRunnerResult} with outcome {@code BLOCKED}; an
 * already-satisfied request returns {@code NO_CHANGE}. The actual writes are delegated to the
 * canonical governed API {@link KnowledgeQuestionAnswerPublicationService} under the dedicated,
 * non-login technical actors from {@link GovernedPilotServiceActors} — this runner never issues raw
 * SQL writes and never invents a new publication path.
 */
@Service
public class TaxiaPilotGovernedRunner {

    private static final Logger log = LoggerFactory.getLogger(TaxiaPilotGovernedRunner.class);

    private final KnowledgeQuestionAnswerRepository qaRepository;
    private final KnowledgeQuestionAnswerPublicationService publicationService;
    private final AtFaqProperties atFaqProperties;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public TaxiaPilotGovernedRunner(
            KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeQuestionAnswerPublicationService publicationService,
            AtFaqProperties atFaqProperties,
            DataSource dataSource,
            JdbcTemplate jdbc) {
        this.qaRepository = qaRepository;
        this.publicationService = publicationService;
        this.atFaqProperties = atFaqProperties;
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------------------
    // status — read-only preflight (READINESS=READY|BLOCKED)
    // -------------------------------------------------------------------------

    /**
     * Read-only preflight for a single explicit {@code externalKey}. Never writes; may run even with
     * the E9C flag off (it reports the flag state). {@code READINESS=READY} means the base is the
     * pilot database on loopback and exactly one target resolved; anything else is
     * {@code READINESS=BLOCKED}.
     */
    public PilotRunnerResult status(String externalKey) {
        PilotRunnerResult.Builder b = PilotRunnerResult.of(PilotRunnerAction.STATUS);
        b.detail("externalKey=" + display(externalKey));
        b.detail("sourceSystem=" + atFaqProperties.getSourceSystem());
        b.detail("flagEnabled=" + atFaqProperties.isE9cPilotEnabled());

        String baseError = baseGate();
        if (baseError != null) {
            b.detail("base=BLOCKED: " + baseError);
            return b.build(PilotRunnerOutcome.BLOCKED, false);
        }
        b.detail("base=OK (knowledgeflow_pilot, loopback)");

        Resolution r = resolve(externalKey);
        if (!r.ok()) {
            b.detail("target=BLOCKED: " + r.reason());
            return b.build(PilotRunnerOutcome.BLOCKED, false);
        }
        KnowledgeQuestionAnswer qa = r.qa();
        b.target(qa.getId());
        b.detail("targetQaId=" + qa.getId());
        b.detail("organizationId=" + qa.getOrganization().getId());
        b.detail("published=" + qa.isPublished());
        b.detail("embeddingRows=" + embeddingRows(qa.getId()));
        b.detail("eligibleForRag=" + qa.isEligibleForRag());
        return b.build(PilotRunnerOutcome.READY, false);
    }

    // -------------------------------------------------------------------------
    // publish-one (RESULT=PUBLISHED|BLOCKED|NO_CHANGE)
    // -------------------------------------------------------------------------

    /**
     * Publishes exactly one explicit Q&A (index + mark published) via the canonical governed API,
     * under the dedicated non-login publisher actor. Requires the E9C flag. Idempotent: an
     * already-published target yields {@code NO_CHANGE}.
     */
    public PilotRunnerResult publishOne(String externalKey) {
        PilotRunnerResult.Builder b = PilotRunnerResult.of(PilotRunnerAction.PUBLISH_ONE);
        b.detail("externalKey=" + display(externalKey));

        if (!atFaqProperties.isE9cPilotEnabled()) {
            b.detail("flag=BLOCKED: AT_FAQ_E9C_PILOT_ENABLED is false (writes require it true)");
            return b.build(PilotRunnerOutcome.BLOCKED, false);
        }
        String baseError = baseGate();
        if (baseError != null) {
            b.detail("base=BLOCKED: " + baseError);
            return b.build(PilotRunnerOutcome.BLOCKED, false);
        }
        Resolution r = resolve(externalKey);
        if (!r.ok()) {
            b.detail("target=BLOCKED: " + r.reason());
            return b.build(PilotRunnerOutcome.BLOCKED, false);
        }
        KnowledgeQuestionAnswer qa = r.qa();
        b.target(qa.getId());
        b.detail("targetQaId=" + qa.getId());

        if (qa.isPublished()) {
            b.detail("already published; nothing to do (idempotent)");
            return b.build(PilotRunnerOutcome.NO_CHANGE, false);
        }

        try {
            publicationService.publish(
                    qa.getOrganization().getId(),
                    GovernedPilotServiceActors.publisherActorId(),
                    GovernedPilotServiceActors.PUBLISHER_IDENTITY,
                    qa.getId());
        } catch (BusinessException e) {
            b.detail("publish refused: " + e.getMessage());
            return b.build(PilotRunnerOutcome.BLOCKED, false);
        }

        b.detail("publisher=" + GovernedPilotServiceActors.PUBLISHER_IDENTITY);
        b.detail("embeddingRows=" + embeddingRows(qa.getId()));
        log.info("TaxiaPilotGovernedRunner published one governed pilot Q&A: {}", qa.getId());
        return b.build(PilotRunnerOutcome.PUBLISHED, true);
    }

    // -------------------------------------------------------------------------
    // rollback-one (RESULT=ROLLED_BACK|BLOCKED|NO_CHANGE)
    // -------------------------------------------------------------------------

    /**
     * Rolls back exactly one explicit Q&A (unpublish + de-index) via the canonical governed API,
     * under the dedicated non-login rollback actor, recording the mandatory motive. Requires the
     * E9C flag. Idempotent: a target that is not published yields {@code NO_CHANGE}.
     */
    public PilotRunnerResult rollbackOne(String externalKey, AtFaqRollbackMotive motive) {
        PilotRunnerResult.Builder b = PilotRunnerResult.of(PilotRunnerAction.ROLLBACK_ONE);
        b.detail("externalKey=" + display(externalKey));

        if (motive == null) {
            b.detail("motive=BLOCKED: a rollback motive is mandatory");
            return b.build(PilotRunnerOutcome.BLOCKED, false);
        }
        if (!atFaqProperties.isE9cPilotEnabled()) {
            b.detail("flag=BLOCKED: AT_FAQ_E9C_PILOT_ENABLED is false (writes require it true)");
            return b.build(PilotRunnerOutcome.BLOCKED, false);
        }
        String baseError = baseGate();
        if (baseError != null) {
            b.detail("base=BLOCKED: " + baseError);
            return b.build(PilotRunnerOutcome.BLOCKED, false);
        }
        Resolution r = resolve(externalKey);
        if (!r.ok()) {
            b.detail("target=BLOCKED: " + r.reason());
            return b.build(PilotRunnerOutcome.BLOCKED, false);
        }
        KnowledgeQuestionAnswer qa = r.qa();
        b.target(qa.getId());
        b.detail("targetQaId=" + qa.getId());

        if (!qa.isPublished()) {
            b.detail("not published; nothing to roll back (idempotent)");
            return b.build(PilotRunnerOutcome.NO_CHANGE, false);
        }

        try {
            publicationService.unpublish(
                    qa.getOrganization().getId(),
                    GovernedPilotServiceActors.rollbackActorId(),
                    qa.getId(),
                    motive.auditDetail());
        } catch (BusinessException e) {
            b.detail("rollback refused: " + e.getMessage());
            return b.build(PilotRunnerOutcome.BLOCKED, false);
        }

        b.detail("rolledBackBy=" + GovernedPilotServiceActors.ROLLBACK_IDENTITY);
        b.detail("reason=" + motive.auditDetail());
        b.detail("embeddingRows=" + embeddingRows(qa.getId()));
        log.info("TaxiaPilotGovernedRunner rolled back one governed pilot Q&A: {}", qa.getId());
        return b.build(PilotRunnerOutcome.ROLLED_BACK, true);
    }

    // -------------------------------------------------------------------------
    // internals
    // -------------------------------------------------------------------------

    /** Re-runs the pilot base check. Returns {@code null} when OK, else the refusal message. */
    private String baseGate() {
        try {
            PilotDatasourceGuard.validate(dataSource);
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    /** Resolves a single target by (sourceSystem, externalKey), enforcing an exactly-one match. */
    private Resolution resolve(String externalKey) {
        if (externalKey == null || externalKey.isBlank()) {
            return Resolution.blocked("externalKey is required");
        }
        String key = externalKey.trim();
        String sourceSystem = atFaqProperties.getSourceSystem();
        List<KnowledgeQuestionAnswer> found =
                qaRepository.findBySourceSystemAndExternalKey(sourceSystem, key);
        if (found.isEmpty()) {
            return Resolution.blocked(
                    "no Q&A found for sourceSystem='" + sourceSystem + "' externalKey='" + key + "'");
        }
        if (found.size() > 1) {
            return Resolution.blocked(
                    "ambiguous: " + found.size() + " Q&A match sourceSystem='" + sourceSystem
                            + "' externalKey='" + key + "' — refusing (N=1 only)");
        }
        return Resolution.ok(found.get(0));
    }

    private long embeddingRows(UUID qaId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_qa_embeddings WHERE knowledge_qa_id = ?::uuid",
                Long.class, qaId.toString());
        return n != null ? n : 0L;
    }

    private static String display(String externalKey) {
        return externalKey == null ? "<null>" : externalKey.trim();
    }

    private record Resolution(boolean ok, KnowledgeQuestionAnswer qa, String reason) {
        static Resolution ok(KnowledgeQuestionAnswer qa) {
            return new Resolution(true, qa, null);
        }

        static Resolution blocked(String reason) {
            return new Resolution(false, null, reason);
        }
    }
}
