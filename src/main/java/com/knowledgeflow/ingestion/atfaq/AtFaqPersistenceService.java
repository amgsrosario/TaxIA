package com.knowledgeflow.ingestion.atfaq;

import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.service.AuditService;
import com.knowledgeflow.knowledge.entity.KnowledgeQuestionAnswer;
import com.knowledgeflow.knowledge.entity.KnowledgeSourceReference;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import com.knowledgeflow.knowledge.repository.KnowledgeQuestionAnswerRepository;
import com.knowledgeflow.knowledge.repository.KnowledgeSourceReferenceRepository;
import com.knowledgeflow.knowledge.service.KnowledgeQuestionAnswerNormalizer;
import com.knowledgeflow.organizations.entity.Organization;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional writes of the AT FAQ import. Each public method is one unit of
 * work: if anything fails, the RAW item and the quarantined Q&A roll back
 * together — no half-imported state.
 * <p>
 * Quarantine invariants enforced here:
 * <ul>
 *   <li>created Q&A entries stay IMPORTED (never VALIDATED / PUBLISHED);</li>
 *   <li>{@code requiresHumanValidation} is always true;</li>
 *   <li>no embedding is ever created (embeddings only happen at publication);</li>
 *   <li>original AT text goes to the immutable original fields — the curated
 *       answer fields stay empty until a human writes the TaxIA synthesis.</li>
 * </ul>
 */
@Service
public class AtFaqPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(AtFaqPersistenceService.class);

    private final AtFaqRawItemRepository rawItemRepository;
    private final AtFaqPageSnapshotRepository snapshotRepository;
    private final KnowledgeQuestionAnswerRepository qaRepository;
    private final KnowledgeSourceReferenceRepository sourceRepository;
    private final KnowledgeQuestionAnswerNormalizer qaNormalizer;
    private final AuditService auditService;
    private final AtFaqProperties properties;

    public AtFaqPersistenceService(
            AtFaqRawItemRepository rawItemRepository,
            AtFaqPageSnapshotRepository snapshotRepository,
            KnowledgeQuestionAnswerRepository qaRepository,
            KnowledgeSourceReferenceRepository sourceRepository,
            KnowledgeQuestionAnswerNormalizer qaNormalizer,
            AuditService auditService,
            AtFaqProperties properties) {
        this.rawItemRepository = rawItemRepository;
        this.snapshotRepository = snapshotRepository;
        this.qaRepository = qaRepository;
        this.sourceRepository = sourceRepository;
        this.qaNormalizer = qaNormalizer;
        this.auditService = auditService;
        this.properties = properties;
    }

    public record NewItemData(
            String officialFaqId,
            String question,
            String answer,
            String subcategory,
            String sourceUrl,
            String pageTitle,
            String contentHash,
            List<String> legalReferences,
            List<String> links) {
    }

    public record ImportOutcome(AtFaqRawItem rawItem, boolean qaCreated, UUID qaId) {
    }

    // -------------------------------------------------------------------------
    // New item: RAW + quarantined Q&A in one transaction
    // -------------------------------------------------------------------------

    @Transactional
    public ImportOutcome importNewItem(Organization org, UUID actingUserId, NewItemData data) {
        AtFaqRawItem raw = buildRawItem(org, data, null);
        raw.markReadyForImport();
        rawItemRepository.save(raw);

        Optional<KnowledgeQuestionAnswer> existingQa =
                qaRepository.findByOrganizationIdAndSourceSystemAndExternalKey(
                        org.getId(), properties.getSourceSystem(), externalKey(data.officialFaqId()));

        boolean qaCreated = false;
        UUID qaId;
        if (existingQa.isPresent()) {
            // The Q&A layer already has this FAQ (e.g. previous pilot run) — link, don't duplicate.
            qaId = existingQa.get().getId();
        } else {
            KnowledgeQuestionAnswer qa = createQuarantinedQa(org, data);
            qaRepository.save(qa);
            sourceRepository.save(buildSourceReference(qa, data));
            qaId = qa.getId();
            qaCreated = true;
        }

        raw.markImported(qaId);
        rawItemRepository.save(raw);

        auditService.record(org.getId(), actingUserId, AuditAction.AT_FAQ_ITEM_NEW,
                "AtFaqRawItem", raw.getId(),
                "officialFaqId=%s subcategory=%s qaCreated=%s".formatted(
                        data.officialFaqId(), data.subcategory(), qaCreated));

        log.debug("AT FAQ imported: id={} qaCreated={}", data.officialFaqId(), qaCreated);
        return new ImportOutcome(raw, qaCreated, qaId);
    }

    // -------------------------------------------------------------------------
    // Changed item: supersede previous version, keep it forever
    // -------------------------------------------------------------------------

    @Transactional
    public AtFaqRawItem registerChangedItem(
            Organization org, UUID actingUserId, AtFaqRawItem previous, NewItemData data) {

        previous.supersede();
        // Flush now: Hibernate would otherwise order the INSERT of the new
        // version before this UPDATE, tripping the partial unique index
        // (one active row per org+authority+faq while superseded = FALSE).
        rawItemRepository.saveAndFlush(previous);

        AtFaqRawItem replacement = buildRawItem(org, data, previous.getId());
        replacement.markChangedAtSource();
        rawItemRepository.save(replacement);

        auditService.record(org.getId(), actingUserId, AuditAction.AT_FAQ_ITEM_CHANGED,
                "AtFaqRawItem", replacement.getId(),
                "officialFaqId=%s previousVersionId=%s".formatted(
                        data.officialFaqId(), previous.getId()));
        return replacement;
    }

    // -------------------------------------------------------------------------
    // Unchanged item
    // -------------------------------------------------------------------------

    @Transactional
    public void markItemSeen(UUID rawItemId, OffsetDateTime when) {
        rawItemRepository.findById(rawItemId).ifPresent(item -> {
            item.markSeen(when);
            rawItemRepository.save(item);
        });
    }

    /** Bumps lastSeenAt of all active items of a page that answered 304 Not Modified. */
    @Transactional
    public int markPageItemsSeen(UUID organizationId, String pageUrl, OffsetDateTime when) {
        List<AtFaqRawItem> items =
                rawItemRepository.findByOrganizationIdAndSourceUrlAndSupersededFalse(organizationId, pageUrl);
        items.forEach(item -> item.markSeen(when));
        rawItemRepository.saveAll(items);
        return items.size();
    }

    // -------------------------------------------------------------------------
    // Possible removals: only after 2+ consecutive missing runs
    // -------------------------------------------------------------------------

    /**
     * Registers a miss for every active item of the processed subcategories
     * that was not seen in this run. Returns the ids of items that crossed the
     * two-miss threshold and are now flagged POSSIBLY_REMOVED.
     */
    @Transactional
    public List<AtFaqRawItem> processMisses(
            Organization org, UUID actingUserId,
            List<String> processedSubcategories, Set<String> seenOfficialIds) {

        List<AtFaqRawItem> flagged = new java.util.ArrayList<>();
        for (AtFaqRawItem item : rawItemRepository.findByOrganizationIdAndSupersededFalse(org.getId())) {
            if (!processedSubcategories.contains(item.getSubcategory())) continue;
            if (seenOfficialIds.contains(item.getOfficialFaqId())) continue;
            if (item.getIngestionStatus() == AtFaqIngestionStatus.POSSIBLY_REMOVED) continue;

            item.registerMiss();
            rawItemRepository.save(item);
            if (item.isSourceRemoved()) {
                flagged.add(item);
                auditService.record(org.getId(), actingUserId, AuditAction.AT_FAQ_ITEM_POSSIBLY_REMOVED,
                        "AtFaqRawItem", item.getId(),
                        "officialFaqId=%s consecutiveMisses=%d".formatted(
                                item.getOfficialFaqId(), item.getConsecutiveMissCount()));
            }
        }
        return flagged;
    }

    // -------------------------------------------------------------------------
    // Page snapshots (conditional-GET cache)
    // -------------------------------------------------------------------------

    @Transactional
    public void recordPageFetch(Organization org, String url, String contentHash,
                                String etag, String lastModified) {
        AtFaqPageSnapshot snapshot = snapshotRepository
                .findByOrganizationIdAndUrl(org.getId(), url)
                .orElseGet(() -> new AtFaqPageSnapshot(org, url));
        snapshot.recordFetch(contentHash, etag, lastModified);
        snapshotRepository.save(snapshot);
    }

    @Transactional
    public void recordPageNotModified(UUID organizationId, String url) {
        snapshotRepository.findByOrganizationIdAndUrl(organizationId, url)
                .ifPresent(snapshot -> {
                    snapshot.recordNotModified();
                    snapshotRepository.save(snapshot);
                });
    }

    // -------------------------------------------------------------------------
    // Builders
    // -------------------------------------------------------------------------

    private AtFaqRawItem buildRawItem(Organization org, NewItemData data, UUID previousVersionId) {
        AtFaqRawItem raw = new AtFaqRawItem(
                org,
                data.officialFaqId(),
                properties.getSourceAuthority(),
                properties.getCategory(),
                data.subcategory(),
                data.question(),
                data.answer(),
                data.sourceUrl(),
                data.pageTitle(),
                data.contentHash(),
                AtFaqHtmlParser.PARSER_VERSION,
                OffsetDateTime.now());
        raw.setPreviousVersionId(previousVersionId);
        raw.setDetectedLegalReferences(joinLines(data.legalReferences()));
        raw.setDetectedLinks(joinLines(data.links()));
        return raw;
    }

    /**
     * Quarantined Q&A: original AT text in the immutable original fields;
     * curated fields (shortAnswer/technicalAnswer — the future TaxIA synthesis)
     * intentionally left empty. The entity's own guards make VALIDATED
     * impossible without a curated answer, so the raw AT text can never be
     * mistaken for a validated TaxIA answer.
     */
    private KnowledgeQuestionAnswer createQuarantinedQa(Organization org, NewItemData data) {
        KnowledgeQuestionAnswer qa = new KnowledgeQuestionAnswer(
                org,
                data.question(),
                data.answer(),
                properties.getSourceSystem(),
                externalKey(data.officialFaqId()));
        qa.updateCuration(
                qaNormalizer.normalizeQuestion(data.question()),
                null,
                null,
                KnowledgeTopic.IVA,
                data.subcategory(),
                "PT",
                KnowledgeRiskLevel.MEDIUM,
                true,
                null,
                null,
                ("Importado automaticamente das FAQs públicas da AT (%s). "
                        + "O texto original é fonte e evidência — não é resposta canónica. "
                        + "Requer revisão humana e síntese própria antes de qualquer publicação. "
                        + "Fonte: %s").formatted(data.officialFaqId(), data.sourceUrl()));
        return qa;
    }

    private KnowledgeSourceReference buildSourceReference(KnowledgeQuestionAnswer qa, NewItemData data) {
        KnowledgeSourceReference ref = new KnowledgeSourceReference(
                qa, KnowledgeSourceType.OFFICIAL_FAQ, properties.getSourceTitle());
        String legalRefs = data.legalReferences().isEmpty()
                ? null
                : String.join("; ", data.legalReferences());
        ref.update(
                KnowledgeSourceType.OFFICIAL_FAQ,
                properties.getSourceTitle(),
                legalRefs,
                data.sourceUrl(),
                null,
                null,
                null,
                null,
                "FAQ %s — %s. Referências legais detectadas automaticamente; não validadas juridicamente."
                        .formatted(data.officialFaqId(), properties.getSourceAuthority()));
        return ref;
    }

    private String externalKey(String officialFaqId) {
        return "AT-FAQ-" + officialFaqId;
    }

    private static String joinLines(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join("\n", values);
    }
}
