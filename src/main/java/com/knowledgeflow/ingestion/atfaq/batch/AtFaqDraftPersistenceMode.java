package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Mode for governed AT-FAQ draft persistence (Bloco E — E8B.2).
 *
 * <p>"Persistir draft não é criar trabalho humano. É preparar conhecimento para publicação
 * governada, automática quando segura." Every value here persists a curable draft in the
 * conservative {@code IMPORTED} state and <b>never</b> publishes, <b>never</b> indexes and
 * <b>never</b> calls the publication service. The mode only records the provenance of the
 * persistence run.
 */
public enum AtFaqDraftPersistenceMode {

    /** Persistence into an isolated test database (Testcontainers). No publication, no indexing. */
    TEST_ISOLATED,

    /** Persistence gated by a verified DRY-RUN report. No publication, no indexing. */
    DRY_RUN_VERIFIED
}
