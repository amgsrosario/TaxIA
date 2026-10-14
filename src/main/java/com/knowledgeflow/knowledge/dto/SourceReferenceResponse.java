package com.knowledgeflow.knowledge.dto;

import com.knowledgeflow.ai.documented.FreshnessStatus;
import com.knowledgeflow.ai.documented.SourceFreshnessPolicy;
import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record SourceReferenceResponse(
        UUID id,
        KnowledgeSourceType sourceType,
        String title,
        String legalReference,
        String url,
        UUID documentId,
        UUID fragmentId,
        LocalDate validFrom,
        LocalDate validTo,
        String notes,
        OffsetDateTime createdAt,
        // C8 / ADR-008: dados governados de verificação e actualidade CALCULADA (só leitura)
        OffsetDateTime verifiedAt,
        UUID verifiedBy,
        boolean consolidatedVersionConfirmed,
        boolean stableMatter,
        String stableMatterReason,
        OffsetDateTime stableMatterMarkedAt,
        UUID stableMatterMarkedBy,
        FreshnessStatus freshnessStatus,
        String freshnessReason,
        LocalDate currentUntil,
        LocalDate stableUntil,
        boolean revalidationNeeded
) {
    /** Com a actualidade calculada pela política governada (relógio injectado). */
    public static SourceReferenceResponse from(KnowledgeSourceReference src, SourceFreshnessPolicy policy) {
        SourceFreshnessPolicy.Assessment f = policy.assess(facts(src));
        return new SourceReferenceResponse(
                src.getId(), src.getSourceType(), src.getTitle(), src.getLegalReference(), src.getUrl(),
                src.getDocumentId(), src.getFragmentId(), src.getValidFrom(), src.getValidTo(), src.getNotes(),
                src.getCreatedAt(), src.getVerifiedAt(), src.getVerifiedBy(), src.isConsolidatedVersionConfirmed(),
                src.isStableMatter(), src.getStableMatterReason(), src.getStableMatterMarkedAt(),
                src.getStableMatterMarkedBy(), f.status(), f.reason(), f.currentUntil(), f.stableUntil(),
                f.revalidationNeeded());
    }

    public static SourceFreshnessPolicy.Facts facts(KnowledgeSourceReference src) {
        return new SourceFreshnessPolicy.Facts(src.getSourceType(), src.getTitle(), src.getLegalReference(),
                src.getValidTo(), src.getVerifiedAt(), src.getVerifiedBy() != null,
                src.isConsolidatedVersionConfirmed(), src.isStableMatter(), src.getStableMatterReason(),
                src.getStableMatterMarkedBy() != null, src.getValidFrom());
    }

    /** Legado: sem política injectada (relógio do sistema). */
    public static SourceReferenceResponse from(KnowledgeSourceReference src) {
        return from(src, new SourceFreshnessPolicy());
    }
}
