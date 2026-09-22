package com.knowledgeflow.ingestion.atfaq.batch;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Canonical, deterministic identities for the dedicated <b>E9C governed-pilot service actors</b> —
 * Bloco E, E9C-pilot-actor-prep (PROMPT 88).
 *
 * <p>Frase-mestra: "A publicação e o rollback governados do piloto agem sob uma identidade técnica
 * própria, auditável e sem login humano — nunca sob um utilizador humano real (ex.:
 * {@code piloto.admin@taxia.local})."
 *
 * <p>The governed flows already derive their audit actor deterministically from the caller-supplied
 * {@code publishedBy}/{@code rolledBackBy} string (see
 * {@link AtFaqGovernedPublicationExecutor#deterministicActor(String)} and
 * {@link AtFaqGovernedRollbackService#deterministicActor(String)}). This class fixes the two
 * canonical identity strings the pilot must feed to those flows, together with the human-facing
 * {@code email}/{@code fullName} and the exact {@code users}-row shape required to satisfy the
 * {@code audit_events → users} foreign key. Because the actor id is computed by calling the very
 * same {@code deterministicActor} seed, the row provisioned here is guaranteed to be the row the
 * executor and the rollback service will stamp — no drift between "expected actor" and "real row".
 *
 * <p><b>Two actors, by function.</b> Publisher and rollback are kept separate so the audit trail
 * attributes each governed effect (publish vs. unpublish/de-index) to a distinct technical identity,
 * mirroring the two distinct deterministic seeds the codebase already uses.
 *
 * <p><b>Non-login by construction.</b> Each actor is persisted with:
 * <ul>
 *   <li>{@code status = DISABLED} — the login path rejects a non-{@code ACTIVE} user;</li>
 *   <li>a password hash sentinel that is <em>not</em> a BCrypt hash, so
 *       {@code PasswordEncoder.matches(anything, hash)} is always {@code false};</li>
 *   <li>no {@code organization_users} membership — and login resolves the user <em>through</em> a
 *       membership first, so with none it fails before any password check.</li>
 * </ul>
 * The three together make interactive authentication impossible, not merely inconvenient.
 *
 * <p><b>Never auto-provisions.</b> This is a plain final class with a private constructor and no
 * Spring stereotype, no {@code @PostConstruct}, no scheduler. {@link #provision(JdbcTemplate)} is
 * only ever invoked explicitly — by an isolated test today, and by the future controlled pilot
 * runner/bootstrap — so merely importing the class can never create rows in any real database.
 * Provisioning is idempotent (insert-if-absent by primary key): a second call is a no-op that
 * neither duplicates nor reactivates an actor.
 */
public final class GovernedPilotServiceActors {

    /** {@code publishedBy} identity fed to the publication executor; seeds the deterministic actor id. */
    public static final String PUBLISHER_IDENTITY = "taxia-e9c-pilot-publisher";
    public static final String PUBLISHER_EMAIL = "taxia-e9c-pilot-publisher@service.local";
    public static final String PUBLISHER_FULL_NAME = "TaxIA E9C Pilot Publisher (service account)";

    /** {@code rolledBackBy} identity fed to the rollback service; seeds the deterministic actor id. */
    public static final String ROLLBACK_IDENTITY = "taxia-e9c-pilot-rollback";
    public static final String ROLLBACK_EMAIL = "taxia-e9c-pilot-rollback@service.local";
    public static final String ROLLBACK_FULL_NAME = "TaxIA E9C Pilot Rollback (service account)";

    /**
     * Password hash sentinel. Deliberately not a BCrypt hash (BCrypt hashes start with
     * {@code $2a$}/{@code $2b$}/{@code $2y$}), so any {@code PasswordEncoder.matches(raw, SENTINEL)}
     * returns {@code false} for every candidate. Combined with {@link #DISABLED_STATUS} and the
     * absence of any membership, the actor can never authenticate.
     */
    public static final String NON_LOGIN_PASSWORD_HASH = "LOCKED-NO-LOGIN-SERVICE-ACCOUNT";

    /** {@code users.status} value that blocks the login path. */
    public static final String DISABLED_STATUS = "DISABLED";

    private GovernedPilotServiceActors() {
    }

    /** Deterministic id of the pilot publication actor — the exact row {@code publish(...)} stamps. */
    public static UUID publisherActorId() {
        return AtFaqGovernedPublicationExecutor.deterministicActor(PUBLISHER_IDENTITY);
    }

    /** Deterministic id of the pilot rollback actor — the exact row {@code unpublish(...)} stamps. */
    public static UUID rollbackActorId() {
        return AtFaqGovernedRollbackService.deterministicActor(ROLLBACK_IDENTITY);
    }

    /**
     * Idempotently provisions both non-login service-account users in the given database.
     *
     * <p>Insert-if-absent by primary key ({@code ON CONFLICT (id) DO NOTHING}): a second run inserts
     * nothing and never duplicates or reactivates an actor. Callers must target an isolated or pilot
     * database explicitly — this method is never invoked automatically.
     *
     * @return the number of rows actually inserted by this call (0, 1 or 2).
     */
    public static int provision(JdbcTemplate jdbc) {
        int inserted = 0;
        inserted += provisionOne(jdbc, publisherActorId(), PUBLISHER_EMAIL, PUBLISHER_FULL_NAME);
        inserted += provisionOne(jdbc, rollbackActorId(), ROLLBACK_EMAIL, ROLLBACK_FULL_NAME);
        return inserted;
    }

    private static int provisionOne(JdbcTemplate jdbc, UUID id, String email, String fullName) {
        return jdbc.update(
                "INSERT INTO users (id, email, full_name, password_hash, status, created_at, updated_at) "
                        + "VALUES (?::uuid, ?, ?, ?, ?, now(), now()) "
                        + "ON CONFLICT (id) DO NOTHING",
                id.toString(), email, fullName, NON_LOGIN_PASSWORD_HASH, DISABLED_STATUS);
    }
}
