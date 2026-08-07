package com.knowledgeflow.ingestion.atfaq.batch;

/**
 * Execution mode for the governed AT-FAQ publication dry-run (Bloco E — E8B.1).
 *
 * <p>"Ensaiar publicação não é publicar." Every value in this enum implies <b>zero real
 * effects</b>: no persistence, no publication, no indexing, no external call. The mode only
 * describes how far the rehearsal goes; it never authorizes a side effect.
 */
public enum AtFaqPublicationDryRunMode {

    /** Full rehearsal: re-check guards and build simulated publication commands. No real effects. */
    DRY_RUN_ONLY,

    /** Guard validation only: re-check guards without building publication intent. No real effects. */
    VALIDATE_ONLY
}
