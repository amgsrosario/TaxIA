package com.knowledgeflow.ingestion.atfaq;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Classifies a freshly parsed FAQ against the active RAW item with the same
 * official id:
 * <ul>
 *   <li>no active row → NEW;</li>
 *   <li>same content hash → UNCHANGED;</li>
 *   <li>different hash → CHANGED (the previous version is always preserved).</li>
 * </ul>
 * Removal is never decided here from a single run — see
 * {@link AtFaqRawItem#registerMiss()} (two consecutive misses required).
 */
@Component
public class AtFaqChangeDetector {

    public enum ItemChange { NEW, UNCHANGED, CHANGED }

    public record Assessment(ItemChange change, AtFaqRawItem existing) {
    }

    private final AtFaqRawItemRepository repository;

    public AtFaqChangeDetector(AtFaqRawItemRepository repository) {
        this.repository = repository;
    }

    public Assessment assess(UUID organizationId, String sourceAuthority,
                             String officialFaqId, String contentHash) {
        Optional<AtFaqRawItem> existing = repository
                .findByOrganizationIdAndSourceAuthorityAndOfficialFaqIdAndSupersededFalse(
                        organizationId, sourceAuthority, officialFaqId);
        if (existing.isEmpty()) {
            return new Assessment(ItemChange.NEW, null);
        }
        AtFaqRawItem item = existing.get();
        if (item.getContentHash().equals(contentHash)) {
            return new Assessment(ItemChange.UNCHANGED, item);
        }
        return new Assessment(ItemChange.CHANGED, item);
    }
}
