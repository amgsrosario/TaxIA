package com.knowledgeflow.ingestion.atfaq.pilot;

import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.config.pilot.PilotDatasourceGuard;
import com.knowledgeflow.ingestion.atfaq.batch.GovernedPilotServiceActors;
import com.knowledgeflow.knowledge.dto.ImportReport;
import com.knowledgeflow.knowledge.dto.KnowledgeQaCurationRequest;
import com.knowledgeflow.knowledge.dto.SourceReferenceRequest;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.enums.KnowledgeCurationStatus;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerCurationService;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerImportService;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.users.entity.User;
import com.knowledgeflow.users.repository.UserRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Guarded one-shot operations tooling that <b>prepares</b> the future E9C real N=1 candidate up to
 * (and only up to) the VALIDATED state — Bloco E, E9C-pilot-operations (PROMPT 100).
 *
 * <p>Frase-mestra: "Preparar o primeiro caso real é uma sequência de gestos deliberados, cada um
 * sobre um único alvo explícito, reutilizando exclusivamente os serviços governados existentes —
 * nunca por arranque, agendador, lote, endpoint ou SQL de inserção próprio."
 *
 * <p><b>Deliberately narrow, and strictly separated from publication.</b> This bean is an inert
 * {@link Service}: no {@code @PostConstruct}, no {@link org.springframework.boot.CommandLineRunner} /
 * {@link org.springframework.boot.ApplicationRunner}, no scheduler, no endpoint, no batch. Merely
 * being in the context does nothing. It acts only when one of its six methods is called explicitly —
 * today by an isolated test, in the future by {@link TaxiaPilotOperationsLauncher} run by hand.
 * Publication, rollback and status live exclusively in {@link TaxiaPilotGovernedRunner}: this class
 * <b>never</b> publishes, indexes, embeds or rolls back, and never advances a Q&amp;A past VALIDATED.
 *
 * <p><b>Six actions, one target, at most one governed write each.</b>
 * <ul>
 *   <li>{@link #provisionActors()} — idempotently provision the two non-login technical actors;</li>
 *   <li>{@link #importOne} — introduce exactly one candidate (status IMPORTED);</li>
 *   <li>{@link #curateOne} — curate a single explicit Q&amp;A (merge, never wipe existing fields);</li>
 *   <li>{@link #addSourceOne} — add one official source to a single explicit Q&amp;A;</li>
 *   <li>{@link #pendingReviewOne} — move a single Q&amp;A to PENDING_REVIEW;</li>
 *   <li>{@link #validateOne} — validate a single Q&amp;A (PENDING_REVIEW → VALIDATED).</li>
 * </ul>
 * Every mutating action reuses the existing governed services
 * ({@link KnowledgeQuestionAnswerImportService}, {@link KnowledgeQuestionAnswerCurationService}) and
 * the canonical service actors ({@link GovernedPilotServiceActors}); this class never issues raw
 * INSERT SQL of its own.
 *
 * <p><b>Fail-closed, no stack traces as interface.</b> Before every action it re-runs the pilot base
 * check ({@link PilotDatasourceGuard#validate(DataSource)} — the datasource must be
 * {@code knowledgeflow_pilot} on a loopback host). The source system must be a single explicit token
 * ({@link PilotSourceSystem}, shared verbatim with the runner) and each target is resolved by its
 * explicit {@code (sourceSystem, externalKey)} pair enforcing an exactly-one match (0 or &gt;1 is
 * refused — N=1 only). Every refusal is a normal {@link PilotOpsResult} with outcome {@code BLOCKED};
 * an already-satisfied request returns {@code NO_CHANGE}.
 *
 * <p><b>Human curator, never a service actor.</b> The curation lifecycle (import → curate →
 * add-source → pending-review → validate) is always attributed to the existing pilot admin
 * {@value #CURATOR_EMAIL}; the technical service actors are used only for publish/rollback, which this
 * class does not perform. The curator is resolved from the database, never created here, and its
 * absence fails closed.
 */
@Service
public class TaxiaPilotOperations {

    private static final Logger log = LoggerFactory.getLogger(TaxiaPilotOperations.class);

    /** The only human identity the preparation lifecycle is ever attributed to. */
    public static final String CURATOR_EMAIL = "piloto.admin@taxia.local";

    private final KnowledgeQuestionAnswerRepository qaRepository;
    private final KnowledgeQuestionAnswerImportService importService;
    private final KnowledgeQuestionAnswerCurationService curationService;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public TaxiaPilotOperations(
            KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeQuestionAnswerImportService importService,
            KnowledgeQuestionAnswerCurationService curationService,
            OrganizationRepository organizationRepository,
            UserRepository userRepository,
            DataSource dataSource,
            JdbcTemplate jdbc) {
        this.qaRepository = qaRepository;
        this.importService = importService;
        this.curationService = curationService;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    // =========================================================================
    // provision-actors (RESULT=PROVISIONED|NO_CHANGE|BLOCKED)
    // =========================================================================

    /**
     * Idempotently provisions the two dedicated non-login technical actors (publisher + rollback)
     * via {@link GovernedPilotServiceActors#provision(JdbcTemplate)}. Never uses the human curator.
     */
    public PilotOpsResult provisionActors() {
        PilotOpsResult.Builder b = PilotOpsResult.of(PilotOpsAction.PROVISION_ACTORS);

        String baseError = baseGate();
        if (baseError != null) {
            b.detail("base=BLOCKED: " + baseError);
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }
        b.detail("base=OK (knowledgeflow_pilot, loopback)");

        int inserted = GovernedPilotServiceActors.provision(jdbc);
        b.detail("publisherActor=" + GovernedPilotServiceActors.publisherActorId());
        b.detail("rollbackActor=" + GovernedPilotServiceActors.rollbackActorId());
        b.detail("rowsInserted=" + inserted);
        if (inserted == 0) {
            b.detail("both technical actors already present; nothing to do (idempotent)");
            return b.build(PilotOpsOutcome.NO_CHANGE, false);
        }
        log.info("TaxiaPilotOperations provisioned {} governed pilot service actor row(s).", inserted);
        return b.build(PilotOpsOutcome.PROVISIONED, true);
    }

    // =========================================================================
    // import-one (RESULT=IMPORTED|NO_CHANGE|BLOCKED)
    // =========================================================================

    /**
     * Introduces exactly one candidate Q&A (status IMPORTED) under an explicit source system, reusing
     * the governed import service. Idempotent per {@code (organization, sourceSystem, externalKey)}: a
     * key that already exists yields {@code NO_CHANGE} without writing.
     */
    public PilotOpsResult importOne(String sourceSystem, String externalKey, String question, String answer) {
        PilotOpsResult.Builder b = PilotOpsResult.of(PilotOpsAction.IMPORT_ONE);
        b.detail("sourceSystem=" + display(sourceSystem));
        b.detail("externalKey=" + display(externalKey));

        String baseError = baseGate();
        if (baseError != null) {
            b.detail("base=BLOCKED: " + baseError);
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }
        String ssError = PilotSourceSystem.validate(sourceSystem);
        if (ssError != null) {
            b.detail("target=BLOCKED: " + ssError);
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }
        if (externalKey == null || externalKey.isBlank()) {
            b.detail("target=BLOCKED: externalKey is required");
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }
        if (question == null || question.isBlank()) {
            b.detail("target=BLOCKED: question is required");
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }
        if (answer == null || answer.isBlank()) {
            b.detail("target=BLOCKED: answer is required");
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }

        UUID curatorId = resolveCurator(b);
        if (curatorId == null) {
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }
        UUID orgId = resolveSingleOrganization(b);
        if (orgId == null) {
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }

        String system = sourceSystem.trim();
        String key = externalKey.trim();

        // Idempotent: an existing (org, sourceSystem, externalKey) is never re-created.
        Optional<KnowledgeQuestionAnswer> existing =
                qaRepository.findByOrganizationIdAndSourceSystemAndExternalKey(orgId, system, key);
        if (existing.isPresent()) {
            b.target(existing.get().getId());
            b.detail("targetQaId=" + existing.get().getId());
            b.detail("already imported for this organization; nothing to do (idempotent)");
            return b.build(PilotOpsOutcome.NO_CHANGE, false);
        }

        String json = singleRowJson(key, question.trim(), answer.trim());
        ImportReport report;
        try {
            report = importService.importJson(
                    orgId, curatorId, system,
                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                    false, 1);
        } catch (IOException | RuntimeException e) {
            b.detail("import refused: " + e.getMessage());
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }

        if (report.imported() != 1) {
            b.detail("import produced no new candidate (imported=" + report.imported()
                    + ", updated=" + report.updated() + ", invalid=" + report.invalid() + ")");
            report.issues().forEach(i -> b.detail("issue: " + i.type() + " — " + i.message()));
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }

        UUID newId = qaRepository.findByOrganizationIdAndSourceSystemAndExternalKey(orgId, system, key)
                .map(KnowledgeQuestionAnswer::getId).orElse(null);
        b.target(newId);
        b.detail("targetQaId=" + newId);
        b.detail("organizationId=" + orgId);
        b.detail("status=IMPORTED");
        log.info("TaxiaPilotOperations imported one governed pilot candidate: {}", newId);
        return b.build(PilotOpsOutcome.IMPORTED, true);
    }

    // =========================================================================
    // curate-one (RESULT=CURATED|BLOCKED)
    // =========================================================================

    /**
     * Curates a single explicit Q&A, reusing the governed curation service. Every provided field
     * overrides; every omitted field ({@code null}) keeps the entity's current value — a merge, never
     * a wipe. Does not change the curation status.
     *
     * <p>Note (PROMPT 100 — "não inventar campos"): the domain has no {@code freshness} or
     * {@code parecerRequirement} attributes, so this tooling exposes only the real curated fields
     * ({@code shortAnswer}, {@code technicalAnswer}, classification). {@code shortAnswer} is what the
     * later {@code validate-one} requires; {@code technicalAnswer} is what RAG eligibility requires.
     */
    public PilotOpsResult curateOne(
            String sourceSystem, String externalKey,
            String shortAnswer, String technicalAnswer, String normalizedQuestion,
            KnowledgeTopic topic, String subtopic, String jurisdiction,
            KnowledgeRiskLevel riskLevel, Boolean requiresHumanValidation, String notes) {

        PilotOpsResult.Builder b = PilotOpsResult.of(PilotOpsAction.CURATE_ONE);
        Resolution r = preflightTarget(b, sourceSystem, externalKey);
        if (!r.ok()) {
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }
        UUID curatorId = resolveCurator(b);
        if (curatorId == null) {
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }

        KnowledgeQuestionAnswer qa = r.qa();
        // Merge: provided values override, omitted (null) keep the current field.
        KnowledgeQaCurationRequest req = new KnowledgeQaCurationRequest(
                normalizedQuestion != null ? normalizedQuestion : qa.getNormalizedQuestion(),
                shortAnswer != null ? shortAnswer : qa.getShortAnswer(),
                technicalAnswer != null ? technicalAnswer : qa.getTechnicalAnswer(),
                topic != null ? topic : qa.getTopic(),
                subtopic != null ? subtopic : qa.getSubtopic(),
                jurisdiction != null ? jurisdiction : qa.getJurisdiction(),
                riskLevel != null ? riskLevel : qa.getRiskLevel(),
                requiresHumanValidation != null ? requiresHumanValidation : qa.isRequiresHumanValidation(),
                qa.getValidFrom(),
                qa.getValidTo(),
                notes != null ? notes : qa.getNotes());
        try {
            curationService.updateCuration(qa.getOrganization().getId(), curatorId, qa.getId(), req);
        } catch (RuntimeException e) {
            b.detail("curation refused: " + e.getMessage());
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }
        b.detail("curator=" + CURATOR_EMAIL);
        b.detail("shortAnswerSet=" + (req.shortAnswer() != null && !req.shortAnswer().isBlank()));
        b.detail("technicalAnswerSet=" + (req.technicalAnswer() != null && !req.technicalAnswer().isBlank()));
        log.info("TaxiaPilotOperations curated one governed pilot candidate: {}", qa.getId());
        return b.build(PilotOpsOutcome.CURATED, true);
    }

    // =========================================================================
    // add-source-one (RESULT=SOURCE_ADDED|BLOCKED)
    // =========================================================================

    /**
     * Adds exactly one official source reference to a single explicit Q&A, reusing the governed
     * curation service (which validates any URL scheme). Append-only: never removes a source.
     */
    public PilotOpsResult addSourceOne(
            String sourceSystem, String externalKey,
            KnowledgeSourceType sourceType, String title, String legalReference, String url, String notes) {

        PilotOpsResult.Builder b = PilotOpsResult.of(PilotOpsAction.ADD_SOURCE_ONE);
        Resolution r = preflightTarget(b, sourceSystem, externalKey);
        if (!r.ok()) {
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }
        if (sourceType == null) {
            b.detail("source=BLOCKED: sourceType is required");
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }
        if (title == null || title.isBlank()) {
            b.detail("source=BLOCKED: title is required");
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }
        UUID curatorId = resolveCurator(b);
        if (curatorId == null) {
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }

        KnowledgeQuestionAnswer qa = r.qa();
        SourceReferenceRequest req = new SourceReferenceRequest(
                sourceType, title, legalReference, url, null, null, null, null, notes);
        try {
            curationService.addSource(qa.getOrganization().getId(), curatorId, qa.getId(), req);
        } catch (RuntimeException e) {
            b.detail("add-source refused: " + e.getMessage());
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }
        b.detail("curator=" + CURATOR_EMAIL);
        b.detail("sourceType=" + sourceType);
        b.detail("title=" + title);
        if (legalReference != null && !legalReference.isBlank()) {
            b.detail("legalReference=" + legalReference);
        }
        log.info("TaxiaPilotOperations added one source to governed pilot candidate: {}", qa.getId());
        return b.build(PilotOpsOutcome.SOURCE_ADDED, true);
    }

    // =========================================================================
    // pending-review-one (RESULT=PENDING_REVIEW|NO_CHANGE|BLOCKED)
    // =========================================================================

    /**
     * Moves a single explicit Q&A to PENDING_REVIEW (from IMPORTED/NEEDS_UPDATE). Idempotent: a
     * target already in PENDING_REVIEW yields {@code NO_CHANGE}; any other status is refused.
     */
    public PilotOpsResult pendingReviewOne(String sourceSystem, String externalKey) {
        PilotOpsResult.Builder b = PilotOpsResult.of(PilotOpsAction.PENDING_REVIEW_ONE);
        Resolution r = preflightTarget(b, sourceSystem, externalKey);
        if (!r.ok()) {
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }
        UUID curatorId = resolveCurator(b);
        if (curatorId == null) {
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }

        KnowledgeQuestionAnswer qa = r.qa();
        b.detail("currentStatus=" + qa.getCurationStatus());
        if (qa.getCurationStatus() == KnowledgeCurationStatus.PENDING_REVIEW) {
            b.detail("already PENDING_REVIEW; nothing to do (idempotent)");
            return b.build(PilotOpsOutcome.NO_CHANGE, false);
        }
        try {
            curationService.markPendingReview(qa.getOrganization().getId(), curatorId, qa.getId());
        } catch (RuntimeException e) {
            b.detail("pending-review refused: " + e.getMessage());
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }
        b.detail("curator=" + CURATOR_EMAIL);
        b.detail("newStatus=PENDING_REVIEW");
        log.info("TaxiaPilotOperations moved one governed pilot candidate to PENDING_REVIEW: {}", qa.getId());
        return b.build(PilotOpsOutcome.PENDING_REVIEW, true);
    }

    // =========================================================================
    // validate-one (RESULT=VALIDATED|NO_CHANGE|BLOCKED)
    // =========================================================================

    /**
     * Validates a single explicit Q&A (PENDING_REVIEW → VALIDATED) via the governed curation service,
     * which enforces at least one source and, for HIGH/CRITICAL risk, the human-validation flag. The
     * entity additionally requires a curated shortAnswer. <b>Never publishes, indexes or embeds</b> —
     * the candidate is left VALIDATED and unpublished. Idempotent: an already-VALIDATED target yields
     * {@code NO_CHANGE}; any status other than PENDING_REVIEW/VALIDATED is refused.
     */
    public PilotOpsResult validateOne(String sourceSystem, String externalKey, String reviewerName) {
        PilotOpsResult.Builder b = PilotOpsResult.of(PilotOpsAction.VALIDATE_ONE);
        Resolution r = preflightTarget(b, sourceSystem, externalKey);
        if (!r.ok()) {
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }
        if (reviewerName == null || reviewerName.isBlank()) {
            b.detail("validate=BLOCKED: reviewerName is required");
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }
        UUID curatorId = resolveCurator(b);
        if (curatorId == null) {
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }

        KnowledgeQuestionAnswer qa = r.qa();
        b.detail("currentStatus=" + qa.getCurationStatus());
        if (qa.getCurationStatus() == KnowledgeCurationStatus.VALIDATED) {
            b.detail("already VALIDATED; nothing to do (idempotent)");
            return b.build(PilotOpsOutcome.NO_CHANGE, false);
        }
        try {
            curationService.validate(qa.getOrganization().getId(), curatorId, reviewerName, qa.getId());
        } catch (RuntimeException e) {
            b.detail("validate refused: " + e.getMessage());
            return b.build(PilotOpsOutcome.BLOCKED, false);
        }
        KnowledgeQuestionAnswer after = qaRepository.findById(qa.getId()).orElse(qa);
        b.detail("curator=" + CURATOR_EMAIL);
        b.detail("reviewer=" + reviewerName);
        b.detail("newStatus=VALIDATED");
        b.detail("published=" + after.isPublished());
        b.detail("eligibleForRag=" + after.isEligibleForRag());
        log.info("TaxiaPilotOperations validated one governed pilot candidate (left unpublished): {}",
                qa.getId());
        return b.build(PilotOpsOutcome.VALIDATED, true);
    }

    // =========================================================================
    // internals
    // =========================================================================

    /** Base gate + source-system + N=1 resolution shared by all target-scoped actions. */
    private Resolution preflightTarget(PilotOpsResult.Builder b, String sourceSystem, String externalKey) {
        b.detail("sourceSystem=" + display(sourceSystem));
        b.detail("externalKey=" + display(externalKey));

        String baseError = baseGate();
        if (baseError != null) {
            b.detail("base=BLOCKED: " + baseError);
            return Resolution.blocked();
        }
        Resolution r = resolve(sourceSystem, externalKey);
        if (!r.ok()) {
            b.detail("target=BLOCKED: " + r.reason());
            return r;
        }
        b.target(r.qa().getId());
        b.detail("targetQaId=" + r.qa().getId());
        return r;
    }

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
     * exactly-one match (N=1). Both parts are mandatory; a missing/blank/wildcard/multi-value source
     * system or a missing external key is refused before any query, and 0 or &gt;1 matches are
     * refused. The source system is never inferred or defaulted.
     */
    private Resolution resolve(String sourceSystem, String externalKey) {
        String ssError = PilotSourceSystem.validate(sourceSystem);
        if (ssError != null) {
            return Resolution.blocked(ssError);
        }
        if (externalKey == null || externalKey.isBlank()) {
            return Resolution.blocked("externalKey is required");
        }
        String system = sourceSystem.trim();
        String key = externalKey.trim();
        List<KnowledgeQuestionAnswer> found = qaRepository.findBySourceSystemAndExternalKey(system, key);
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

    /** Resolves the single human curator (pilot admin). Fails closed if absent. */
    private UUID resolveCurator(PilotOpsResult.Builder b) {
        Optional<User> curator = userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(CURATOR_EMAIL);
        if (curator.isEmpty()) {
            b.detail("curator=BLOCKED: pilot admin '" + CURATOR_EMAIL + "' not found");
            return null;
        }
        return curator.get().getId();
    }

    /** Resolves the single (non-deleted) organization. Fails closed unless exactly one exists. */
    private UUID resolveSingleOrganization(PilotOpsResult.Builder b) {
        List<Organization> orgs = organizationRepository.findAll().stream()
                .filter(o -> o.getDeletedAt() == null)
                .toList();
        if (orgs.size() != 1) {
            b.detail("organization=BLOCKED: expected exactly one organization but found " + orgs.size());
            return null;
        }
        return orgs.get(0).getId();
    }

    /** Builds a single-row JSON import payload — question and answer only; other fields curated later. */
    private static String singleRowJson(String externalKey, String question, String answer) {
        return "[{\"externalKey\":" + jsonString(externalKey)
                + ",\"question\":" + jsonString(question)
                + ",\"answer\":" + jsonString(answer) + "}]";
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String display(String value) {
        return value == null ? "<null>" : value.trim();
    }

    private record Resolution(boolean ok, KnowledgeQuestionAnswer qa, String reason) {
        static Resolution ok(KnowledgeQuestionAnswer qa) {
            return new Resolution(true, qa, null);
        }

        static Resolution blocked(String reason) {
            return new Resolution(false, null, reason);
        }

        static Resolution blocked() {
            return new Resolution(false, null, null);
        }
    }
}
