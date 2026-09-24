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
 * Each call resolves a single target by its explicit {@code (sourceSystem, externalKey)} pair
 * (enforcing an exactly-one match — 0 or &gt;1 is refused), performs at most one governed write, and
 * returns. There is no batch, no "next", and no auto-discovery.
 *
 * <p><b>sourceSystem is explicit, never implicit (PROMPT 96).</b> Every action requires the caller to
 * name the source system (the logical origin namespace the Q&A entered TaxIA through, e.g.
 * {@code at-faq} or {@code taxia-curated}). It is no longer taken from configuration: the runner does
 * not carry a default source system, so it can never silently resolve against the wrong namespace.
 * The value must be a single, non-blank, wildcard-free token; it participates in the N=1 resolution
 * so the same {@code externalKey} living under two different source systems never collides.
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
     * the E9C flag off (it reports the flag state).
     *
     * <p><b>{@code READINESS=READY} is about environment/inspection health, not write authorization.</b>
     * It means only that the base is the pilot database on loopback and exactly one target resolved;
     * anything else is {@code READINESS=BLOCKED}. Whether a write ({@code publish-one}/
     * {@code rollback-one}) would actually be permitted is reported separately and unambiguously on the
     * {@code writeReadiness=...} line (READY only when {@code AT_FAQ_E9C_PILOT_ENABLED=true}). So a
     * healthy, inspectable environment can — and normally will, during preflight — show
     * {@code READINESS=READY} together with {@code writeReadiness=BLOCKED}. READY here never authorizes
     * a publish.
     */
    public PilotRunnerResult status(String sourceSystem, String externalKey) {
        PilotRunnerResult.Builder b = PilotRunnerResult.of(PilotRunnerAction.STATUS);
        b.detail("sourceSystem=" + display(sourceSystem));
        b.detail("externalKey=" + display(externalKey));
        b.detail("flagEnabled=" + atFaqProperties.isE9cPilotEnabled());

        String baseError = baseGate();
        if (baseError != null) {
            b.detail("base=BLOCKED: " + baseError);
            return b.build(PilotRunnerOutcome.BLOCKED, false);
        }
        b.detail("base=OK (knowledgeflow_pilot, loopback)");

        Resolution r = resolve(sourceSystem, externalKey);
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
        // Environment is inspectable (READINESS=READY below). State write authorization explicitly and
        // separately so READY can never be misread as "cleared to publish" (PROMPT 93-CORRECÇÃO).
        boolean writeAllowed = atFaqProperties.isE9cPilotEnabled();
        b.detail("writeReadiness=" + (writeAllowed ? "READY" : "BLOCKED")
                + " (publish-one/rollback-one " + (writeAllowed ? "permitted" : "refused")
                + "; AT_FAQ_E9C_PILOT_ENABLED=" + writeAllowed + ")");
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
    public PilotRunnerResult publishOne(String sourceSystem, String externalKey) {
        PilotRunnerResult.Builder b = PilotRunnerResult.of(PilotRunnerAction.PUBLISH_ONE);
        b.detail("sourceSystem=" + display(sourceSystem));
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
        Resolution r = resolve(sourceSystem, externalKey);
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
    public PilotRunnerResult rollbackOne(String sourceSystem, String externalKey, AtFaqRollbackMotive motive) {
        PilotRunnerResult.Builder b = PilotRunnerResult.of(PilotRunnerAction.ROLLBACK_ONE);
        b.detail("sourceSystem=" + display(sourceSystem));
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
        Resolution r = resolve(sourceSystem, externalKey);
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

    /**
     * Resolves a single target by the explicit {@code (sourceSystem, externalKey)} pair, enforcing an
     * exactly-one match. Both parts are mandatory and fail closed: a missing/blank/wildcard/multi-value
     * source system or a missing external key is refused before any query, and 0 or &gt;1 matches are
     * refused (N=1 only). The source system is never inferred and never defaulted.
     */
    private Resolution resolve(String sourceSystem, String externalKey) {
        String ssError = validateSourceSystem(sourceSystem);
        if (ssError != null) {
            return Resolution.blocked(ssError);
        }
        if (externalKey == null || externalKey.isBlank()) {
            return Resolution.blocked("externalKey is required");
        }
        String system = sourceSystem.trim();
        String key = externalKey.trim();
        List<KnowledgeQuestionAnswer> found =
                qaRepository.findBySourceSystemAndExternalKey(system, key);
        if (found.isEmpty()) {
            return Resolution.blocked(
                    "no Q&A found for sourceSystem='" + system + "' externalKey='" + key + "'");
        }
        if (found.size() > 1) {
            return Resolution.blocked(
                    "ambiguous: " + found.size() + " Q&A match sourceSystem='" + system
                            + "' externalKey='" + key + "' — refusing (N=1 only)");
        }
        return Resolution.ok(found.get(0));
    }

    /**
     * Fail-closed validation of an explicit source system: mandatory, trimmed, non-blank, and exactly
     * one plain token — no wildcards ({@code * % ?}) and no multi-value separators
     * ({@code , ; |} or internal whitespace). Returns {@code null} when valid, else the refusal
     * message. Deliberately no closed global taxonomy: any explicit token such as {@code at-faq} or
     * {@code taxia-curated} is accepted.
     */
    private static String validateSourceSystem(String sourceSystem) {
        if (sourceSystem == null || sourceSystem.isBlank()) {
            return "sourceSystem is required";
        }
        String system = sourceSystem.trim();
        if (system.indexOf('*') >= 0 || system.indexOf('%') >= 0 || system.indexOf('?') >= 0) {
            return "sourceSystem must be a single explicit value without wildcards ('" + system + "')";
        }
        if (system.indexOf(',') >= 0 || system.indexOf(';') >= 0 || system.indexOf('|') >= 0
                || system.matches(".*\\s.*")) {
            return "sourceSystem must be exactly one value without separators ('" + system + "')";
        }
        return null;
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
