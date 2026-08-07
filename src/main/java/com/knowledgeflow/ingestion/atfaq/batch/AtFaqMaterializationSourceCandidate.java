package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import java.util.List;

/**
 * A source attached to a materialized (but not yet persisted or published) AT-FAQ draft
 * (Bloco E — E8A).
 *
 * <p>"Materializar conhecimento não é publicá-lo." This is the auditable projection of an E5
 * {@link AtFaqPreCurationSourceCandidate} onto the minimal shape a future
 * {@code KnowledgeSourceReference} would need. It carries no raw HTML, no chunks, no prompts and
 * no fetched content — URLs are plain strings and are never called.
 *
 * @param type           source type (e.g. {@link KnowledgeSourceType#OFFICIAL_FAQ})
 * @param title          human-readable source label
 * @param url            source URL as a plain string (never fetched); may be null
 * @param legalReference legal reference this source represents; may be null
 * @param official       whether the source is official
 * @param primary        whether this is the primary support
 * @param warnings       non-blocking notes about this source
 */
public record AtFaqMaterializationSourceCandidate(
        KnowledgeSourceType type,
        String title,
        String url,
        String legalReference,
        boolean official,
        boolean primary,
        List<String> warnings) {

    public AtFaqMaterializationSourceCandidate {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
