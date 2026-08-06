package com.knowledgeflow.ingestion.atfaq.batch;

import com.knowledgeflow.ai.documented.AuthorityLevel;
import com.knowledgeflow.ai.documented.FreshnessStatus;
import com.knowledgeflow.ai.documented.SourceDiversity;
import com.knowledgeflow.ai.documented.SourceQuality;
import com.knowledgeflow.ai.documented.SourceRole;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import java.util.List;

/**
 * A proposed source candidate for a pre-curated AT-FAQ item (Bloco E — E5).
 *
 * <p>"A pré-curadoria prepara o caso. Não o autoriza a responder." This is a <b>proposal</b>
 * describing how a source could support the answer — it is never fetched, never validated
 * online and never published. Freshness defaults to {@link FreshnessStatus#UNCERTAIN}: E5 does
 * not confirm actuality by calling any URL.
 *
 * @param type           source type (e.g. {@link KnowledgeSourceType#OFFICIAL_FAQ}, {@link KnowledgeSourceType#LEGISLATION})
 * @param title          human-readable source label
 * @param url            source URL as a plain string (never fetched); may be null for legislation
 * @param legalReference legal reference this candidate represents; may be null for a FAQ candidate
 * @param authorityLevel documental authority of the source
 * @param sourceQuality  qualitative strength of the source
 * @param sourceRole     role in supporting the answer (primary/complementary/derivative)
 * @param sourceCore     normalized "core" identity (URL path or legal reference) for diversity reasoning
 * @param sourceDiversity material diversity of the source set this candidate belongs to
 * @param freshnessStatus actuality of the source (UNCERTAIN unless an explicit marker exists)
 * @param official       whether the source is official
 * @param primary        whether this candidate is the primary support
 * @param warnings       non-blocking notes about this candidate
 */
public record AtFaqPreCurationSourceCandidate(
        KnowledgeSourceType type,
        String title,
        String url,
        String legalReference,
        AuthorityLevel authorityLevel,
        SourceQuality sourceQuality,
        SourceRole sourceRole,
        String sourceCore,
        SourceDiversity sourceDiversity,
        FreshnessStatus freshnessStatus,
        boolean official,
        boolean primary,
        List<String> warnings) {

    public AtFaqPreCurationSourceCandidate {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
