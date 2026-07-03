package com.knowledgeflow.ingestion.atfaq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgeflow.audit.enums.AuditAction;
import com.knowledgeflow.audit.service.AuditService;
import com.knowledgeflow.common.error.ApiErrorCode;
import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.ingestion.atfaq.AtFaqChangeDetector.Assessment;
import com.knowledgeflow.ingestion.atfaq.AtFaqChangeDetector.ItemChange;
import com.knowledgeflow.ingestion.atfaq.AtFaqDiscoveryService.DiscoveredCategory;
import com.knowledgeflow.ingestion.atfaq.AtFaqDiscoveryService.DiscoveryOutcome;
import com.knowledgeflow.ingestion.atfaq.AtFaqExceptions.SecurityBlockedException;
import com.knowledgeflow.ingestion.atfaq.AtFaqExceptions.SourceBlockedException;
import com.knowledgeflow.ingestion.atfaq.AtFaqHtmlParser.CategoryParseResult;
import com.knowledgeflow.ingestion.atfaq.AtFaqHtmlParser.ParsedFaq;
import com.knowledgeflow.ingestion.atfaq.AtFaqHttpClient.FetchResult;
import com.knowledgeflow.ingestion.atfaq.AtFaqPersistenceService.ImportOutcome;
import com.knowledgeflow.ingestion.atfaq.AtFaqPersistenceService.NewItemData;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the AT FAQ ingestion pilot: DISCOVER, DRY_RUN and IMPORT runs.
 * <p>
 * Not transactional on purpose — network I/O must never run inside a database
 * transaction. All writes are delegated to {@link AtFaqPersistenceService},
 * one small transaction per item.
 * <p>
 * Hard guarantees:
 * <ul>
 *   <li>refuses to run while {@code knowledgeflow.ingestion.at-faq.enabled=false};</li>
 *   <li>DRY_RUN writes nothing besides the run record;</li>
 *   <li>IMPORT lands in quarantine only — never VALIDATED, never PUBLISHED,
 *       never embedded;</li>
 *   <li>403/429/503 or any security violation stops the run immediately.</li>
 * </ul>
 */
@Service
public class AtFaqImportService {

    private static final Logger log = LoggerFactory.getLogger(AtFaqImportService.class);
    private static final int MAX_REPORT_ISSUES = 50;

    private final AtFaqProperties properties;
    private final AtFaqHttpClient httpClient;
    private final AtFaqDiscoveryService discoveryService;
    private final AtFaqHtmlParser parser;
    private final AtFaqNormalizer normalizer;
    private final AtFaqLegalReferenceExtractor legalReferenceExtractor;
    private final AtFaqChangeDetector changeDetector;
    private final AtFaqPersistenceService persistenceService;
    private final AtFaqRawItemRepository rawItemRepository;
    private final AtFaqPageSnapshotRepository snapshotRepository;
    private final AtFaqIngestionRunRepository runRepository;
    private final OrganizationRepository organizationRepository;
    private final AuditService auditService;
    private final AtFaqMetrics metrics;
    private final ObjectMapper objectMapper;

    public AtFaqImportService(
            AtFaqProperties properties,
            AtFaqHttpClient httpClient,
            AtFaqDiscoveryService discoveryService,
            AtFaqHtmlParser parser,
            AtFaqNormalizer normalizer,
            AtFaqLegalReferenceExtractor legalReferenceExtractor,
            AtFaqChangeDetector changeDetector,
            AtFaqPersistenceService persistenceService,
            AtFaqRawItemRepository rawItemRepository,
            AtFaqPageSnapshotRepository snapshotRepository,
            AtFaqIngestionRunRepository runRepository,
            OrganizationRepository organizationRepository,
            AuditService auditService,
            AtFaqMetrics metrics,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.discoveryService = discoveryService;
        this.parser = parser;
        this.normalizer = normalizer;
        this.legalReferenceExtractor = legalReferenceExtractor;
        this.changeDetector = changeDetector;
        this.persistenceService = persistenceService;
        this.rawItemRepository = rawItemRepository;
        this.snapshotRepository = snapshotRepository;
        this.runRepository = runRepository;
        this.organizationRepository = organizationRepository;
        this.auditService = auditService;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public AtFaqRunResult discover(UUID organizationId, UUID actingUserId) {
        return execute(organizationId, actingUserId, AtFaqRunMode.DISCOVER, null, null);
    }

    public AtFaqRunResult dryRun(UUID organizationId, UUID actingUserId,
                                 Integer maxPages, Integer maxItems) {
        return execute(organizationId, actingUserId, AtFaqRunMode.DRY_RUN, maxPages, maxItems);
    }

    public AtFaqRunResult importRun(UUID organizationId, UUID actingUserId,
                                    Integer maxPages, Integer maxItems) {
        return execute(organizationId, actingUserId, AtFaqRunMode.IMPORT, maxPages, maxItems);
    }

    public record AtFaqRunResult(UUID runId, AtFaqRunStatus status, AtFaqImportReport report) {
    }

    // -------------------------------------------------------------------------
    // Run skeleton
    // -------------------------------------------------------------------------

    private AtFaqRunResult execute(UUID organizationId, UUID actingUserId,
                                   AtFaqRunMode mode, Integer maxPagesOverride, Integer maxItemsOverride) {
        requireEnabled();
        Organization org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND,
                        "Organization not found: " + organizationId));

        // Overrides can only tighten the configured limits, never widen them.
        int maxPages = clamp(maxPagesOverride, properties.getMaxPages());
        int maxItems = clamp(maxItemsOverride, properties.getMaxItems());

        AtFaqIngestionRun run = new AtFaqIngestionRun(org, mode, actingUserId);
        runRepository.save(run);
        auditService.record(org.getId(), actingUserId, AuditAction.AT_FAQ_DISCOVERY_STARTED,
                "AtFaqIngestionRun", run.getId(), "mode=" + mode);

        RunContext ctx = new RunContext(org, actingUserId, mode, maxPages, maxItems, run.getId());
        try {
            AtFaqImportReport report = pipeline(ctx);
            run.complete(toJson(report));
            runRepository.save(run);
            auditService.record(org.getId(), actingUserId, completionAction(mode),
                    "AtFaqIngestionRun", run.getId(), summaryOf(report));
            metrics.recordIngestionDuration("success", report.durationMillis());
            return new AtFaqRunResult(run.getId(), AtFaqRunStatus.COMPLETED, report);

        } catch (SourceBlockedException e) {
            AtFaqImportReport partial = ctx.buildReport(true);
            run.block(e.getMessage(), toJson(partial));
            runRepository.save(run);
            metrics.recordIngestionDuration("blocked", partial.durationMillis());
            log.warn("AT FAQ run {} blocked by source: {}", run.getId(), e.getMessage());
            return new AtFaqRunResult(run.getId(), AtFaqRunStatus.BLOCKED, partial);

        } catch (SecurityBlockedException e) {
            AtFaqImportReport partial = ctx.buildReport(true);
            run.block(e.getMessage(), toJson(partial));
            runRepository.save(run);
            auditService.record(org.getId(), actingUserId, AuditAction.AT_FAQ_SECURITY_BLOCKED,
                    "AtFaqIngestionRun", run.getId(), e.getMessage());
            metrics.recordIngestionDuration("security_blocked", partial.durationMillis());
            log.warn("AT FAQ run {} blocked by security rule: {}", run.getId(), e.getMessage());
            return new AtFaqRunResult(run.getId(), AtFaqRunStatus.BLOCKED, partial);

        } catch (RuntimeException e) {
            AtFaqImportReport partial = ctx.buildReport(true);
            run.fail(e.getMessage(), toJson(partial));
            runRepository.save(run);
            metrics.recordIngestionDuration("failure", partial.durationMillis());
            log.error("AT FAQ run {} failed", run.getId(), e);
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // Pipeline
    // -------------------------------------------------------------------------

    private AtFaqImportReport pipeline(RunContext ctx) {
        // 1. Index page — always fetched fresh (its body is required for discovery).
        FetchResult index = httpClient.fetch(properties.indexUrl(), null, null, ctx.stats);
        metrics.recordHttpRequest(index.statusCode());
        ctx.pagesFetched++;

        DiscoveryOutcome discovery = discoveryService.discoverFromIndexHtml(
                index.body(), properties.indexUrl());
        ctx.pagesDiscovered = discovery.categories().size();
        ctx.rejectedUrls = discovery.rejectedUrls().size();
        discovery.rejectedUrls().forEach(url ->
                ctx.addIssue("URL rejeitada na descoberta: " + url));
        metrics.recordPagesDiscovered(discovery.categories().size());
        auditService.record(ctx.org.getId(), ctx.actingUserId, AuditAction.AT_FAQ_DISCOVERY_COMPLETED,
                "AtFaqIngestionRun", ctx.runMarker(),
                "categories=%d rejected=%d".formatted(ctx.pagesDiscovered, ctx.rejectedUrls));

        if (ctx.mode == AtFaqRunMode.DISCOVER) {
            discovery.categories().forEach(c ->
                    ctx.addIssue("Categoria autorizada: %s → %s".formatted(c.subcategory(), c.url())));
            return ctx.buildReport(false);
        }

        // 2. Category pages
        for (DiscoveredCategory category : discovery.categories()) {
            if (ctx.pagesFetched >= ctx.maxPages) {
                ctx.truncated = true;
                ctx.addIssue("Limite de páginas atingido (%d) — categorias restantes não processadas"
                        .formatted(ctx.maxPages));
                break;
            }
            if (ctx.exceededBudget(properties.getMaxRunDuration())) {
                ctx.truncated = true;
                ctx.addIssue("Orçamento de tempo do run excedido — execução truncada");
                break;
            }
            if (ctx.itemsProcessed >= ctx.maxItems) {
                ctx.truncated = true;
                break;
            }
            processCategoryPage(ctx, category);
        }

        // 3. Possible removals — never on a single run's evidence alone.
        assessMisses(ctx);

        return ctx.buildReport(false);
    }

    private void processCategoryPage(RunContext ctx, DiscoveredCategory category) {
        AtFaqPageSnapshot snapshot = snapshotRepository
                .findByOrganizationIdAndUrl(ctx.org.getId(), category.url())
                .orElse(null);

        FetchResult page = httpClient.fetch(
                category.url(),
                snapshot != null ? snapshot.getEtag() : null,
                snapshot != null ? snapshot.getLastModified() : null,
                ctx.stats);
        metrics.recordHttpRequest(page.statusCode());
        ctx.pagesFetched++;
        ctx.processedSubcategories.add(category.subcategory());

        if (page.notModified()) {
            ctx.pagesNotModified++;
            if (ctx.mode == AtFaqRunMode.IMPORT) {
                int seen = persistenceService.markPageItemsSeen(
                        ctx.org.getId(), category.url(), ctx.runStartedAt);
                ctx.unchangedItems += seen;
                rawItemRepository
                        .findByOrganizationIdAndSourceUrlAndSupersededFalse(ctx.org.getId(), category.url())
                        .forEach(item -> ctx.seenOfficialIds.add(item.getOfficialFaqId()));
            } else {
                List<AtFaqRawItem> known = rawItemRepository
                        .findByOrganizationIdAndSourceUrlAndSupersededFalse(ctx.org.getId(), category.url());
                ctx.unchangedItems += known.size();
                known.forEach(item -> ctx.seenOfficialIds.add(item.getOfficialFaqId()));
            }
            ctx.addIssue("Página inalterada (304): " + category.url());
            return;
        }

        // Same content hash as the previous fetch → page unchanged even without ETag support.
        String pageHash = normalizer.pageHash(page.body());
        boolean unchangedByHash = snapshot != null && pageHash.equals(snapshot.getContentHash());

        CategoryParseResult parsed = parser.parseCategoryPage(page.body(), category.url());
        ctx.faqsFound += parsed.faqs().size() + parsed.failures().size();
        metrics.recordPageParsed(category.subcategory());

        for (AtFaqHtmlParser.ParseFailure failure : parsed.failures()) {
            ctx.parseFailures++;
            metrics.recordParseFailure(category.subcategory());
            ctx.addIssue("Falha de parsing em %s: %s".formatted(category.url(), failure.reason()));
            if (ctx.mode == AtFaqRunMode.IMPORT) {
                auditService.record(ctx.org.getId(), ctx.actingUserId, AuditAction.AT_FAQ_PARSE_FAILED,
                        "AtFaqIngestionRun", ctx.runMarker(),
                        "url=%s reason=%s".formatted(category.url(), failure.reason()));
            }
        }

        for (ParsedFaq faq : parsed.faqs()) {
            if (ctx.itemsProcessed >= ctx.maxItems) {
                ctx.truncated = true;
                ctx.addIssue("Limite de itens atingido (%d) — FAQs restantes não processadas"
                        .formatted(ctx.maxItems));
                break;
            }
            processFaq(ctx, category, parsed.pageTitle(), faq);
        }

        if (ctx.mode == AtFaqRunMode.IMPORT && !unchangedByHash) {
            persistenceService.recordPageFetch(
                    ctx.org, category.url(), pageHash, page.etag(), page.lastModified());
        }
    }

    private void processFaq(RunContext ctx, DiscoveredCategory category, String pageTitle, ParsedFaq faq) {
        if (!ctx.seenOfficialIds.add(faq.officialFaqId())) {
            ctx.duplicates++;
            ctx.addIssue("FAQ duplicada na fonte: " + faq.officialFaqId());
            return;
        }
        ctx.itemsProcessed++;

        String question = normalizer.normalize(faq.question());
        String answer = normalizer.normalize(faq.answer());
        String hash = normalizer.contentHash(question, answer);
        List<String> legalRefs = legalReferenceExtractor.extract(answer);
        ctx.legalReferencesFound += legalRefs.size();

        Assessment assessment = changeDetector.assess(
                ctx.org.getId(), properties.getSourceAuthority(), faq.officialFaqId(), hash);

        NewItemData data = new NewItemData(
                faq.officialFaqId(), question, answer, category.subcategory(),
                category.url(), pageTitle, hash, legalRefs, faq.links());

        if (ctx.mode == AtFaqRunMode.DRY_RUN) {
            switch (assessment.change()) {
                case NEW -> ctx.newItems++;
                case UNCHANGED -> ctx.unchangedItems++;
                case CHANGED -> {
                    ctx.changedItems++;
                    ctx.addIssue("FAQ alterada na fonte: " + faq.officialFaqId());
                }
            }
            return;
        }

        switch (assessment.change()) {
            case NEW -> {
                ImportOutcome outcome = persistenceService.importNewItem(ctx.org, ctx.actingUserId, data);
                ctx.newItems++;
                if (outcome.qaCreated()) {
                    ctx.importedToQuarantine++;
                } else {
                    ctx.addIssue("FAQ %s já existia na camada Q&A — ligada sem duplicar"
                            .formatted(faq.officialFaqId()));
                }
                metrics.recordItemNew(category.subcategory());
            }
            case UNCHANGED -> {
                persistenceService.markItemSeen(assessment.existing().getId(), ctx.runStartedAt);
                ctx.unchangedItems++;
                metrics.recordItemUnchanged(category.subcategory());
            }
            case CHANGED -> {
                persistenceService.registerChangedItem(ctx.org, ctx.actingUserId, assessment.existing(), data);
                ctx.changedItems++;
                ctx.addIssue("FAQ alterada na fonte: %s — nova versão registada, anterior preservada"
                        .formatted(faq.officialFaqId()));
                metrics.recordItemChanged(category.subcategory());
            }
        }
    }

    private void assessMisses(RunContext ctx) {
        if (ctx.truncated) {
            // A truncated run is not evidence of removal — skip the assessment.
            ctx.addIssue("Execução truncada: avaliação de remoções não efectuada");
            return;
        }
        List<String> processed = new ArrayList<>(ctx.processedSubcategories);
        if (processed.isEmpty()) return;

        if (ctx.mode == AtFaqRunMode.IMPORT) {
            List<AtFaqRawItem> flagged = persistenceService.processMisses(
                    ctx.org, ctx.actingUserId, processed, ctx.seenOfficialIds);
            ctx.possiblyRemoved = flagged.size();
            flagged.forEach(item -> {
                metrics.recordItemRemovedCandidate(item.getSubcategory());
                ctx.addIssue("FAQ possivelmente removida na fonte (2+ execuções): "
                        + item.getOfficialFaqId());
            });
        } else {
            for (AtFaqRawItem item : rawItemRepository
                    .findByOrganizationIdAndSupersededFalse(ctx.org.getId())) {
                if (!processed.contains(item.getSubcategory())) continue;
                if (ctx.seenOfficialIds.contains(item.getOfficialFaqId())) continue;
                if (item.getIngestionStatus() == AtFaqIngestionStatus.POSSIBLY_REMOVED) continue;
                if (item.getConsecutiveMissCount() + 1 >= 2) {
                    ctx.possiblyRemoved++;
                    ctx.addIssue("FAQ candidata a remoção (dry-run): " + item.getOfficialFaqId());
                } else {
                    ctx.addIssue("FAQ ausente nesta execução (1.ª falta, sem acção): "
                            + item.getOfficialFaqId());
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_ERROR,
                    "A ingestão de FAQs da AT está desactivada "
                            + "(knowledgeflow.ingestion.at-faq.enabled=false)");
        }
    }

    private static int clamp(Integer requested, int configured) {
        if (requested == null || requested <= 0) return configured;
        return Math.min(requested, configured);
    }

    private AuditAction completionAction(AtFaqRunMode mode) {
        return switch (mode) {
            case DISCOVER -> AuditAction.AT_FAQ_DISCOVERY_COMPLETED;
            case DRY_RUN -> AuditAction.AT_FAQ_DRY_RUN_COMPLETED;
            case IMPORT -> AuditAction.AT_FAQ_IMPORT_COMPLETED;
        };
    }

    private static String summaryOf(AtFaqImportReport report) {
        return ("pages=%d faqs=%d new=%d unchanged=%d changed=%d possiblyRemoved=%d "
                + "failures=%d imported=%d")
                .formatted(report.pagesFetched(), report.faqsParsed(), report.newItems(),
                        report.unchangedItems(), report.changedItems(), report.possiblyRemoved(),
                        report.parseFailures(), report.importedToQuarantine());
    }

    private String toJson(AtFaqImportReport report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize AT FAQ report", e);
            return null;
        }
    }

    /** Mutable per-run accumulator. */
    private final class RunContext {
        final Organization org;
        final UUID actingUserId;
        final AtFaqRunMode mode;
        final int maxPages;
        final int maxItems;
        final OffsetDateTime runStartedAt = OffsetDateTime.now();
        final long startNanos = System.nanoTime();
        final AtFaqHttpClient.Stats stats = new AtFaqHttpClient.Stats();
        final Set<String> seenOfficialIds = new HashSet<>();
        final Set<String> processedSubcategories = new java.util.LinkedHashSet<>();
        final List<String> issues = new ArrayList<>();

        int pagesDiscovered;
        int pagesFetched;
        int pagesNotModified;
        int faqsFound;
        int parseFailures;
        int newItems;
        int unchangedItems;
        int changedItems;
        int possiblyRemoved;
        int duplicates;
        int importedToQuarantine;
        int legalReferencesFound;
        int rejectedUrls;
        int itemsProcessed;
        boolean truncated;

        private final UUID runId;

        RunContext(Organization org, UUID actingUserId, AtFaqRunMode mode,
                   int maxPages, int maxItems, UUID runId) {
            this.org = org;
            this.actingUserId = actingUserId;
            this.mode = mode;
            this.maxPages = maxPages;
            this.maxItems = maxItems;
            this.runId = runId;
        }

        UUID runMarker() {
            return runId;
        }

        boolean exceededBudget(Duration budget) {
            return Duration.ofNanos(System.nanoTime() - startNanos).compareTo(budget) > 0;
        }

        void addIssue(String issue) {
            if (issues.size() < MAX_REPORT_ISSUES) issues.add(issue);
        }

        AtFaqImportReport buildReport(boolean aborted) {
            Map<String, Integer> statusCodes = new HashMap<>();
            stats.statusCodes().forEach((code, count) -> statusCodes.put(String.valueOf(code), count));
            return new AtFaqImportReport(
                    mode.name(),
                    mode != AtFaqRunMode.IMPORT,
                    pagesDiscovered,
                    pagesFetched,
                    pagesNotModified,
                    faqsFound,
                    itemsProcessed,
                    parseFailures,
                    newItems,
                    unchangedItems,
                    changedItems,
                    possiblyRemoved,
                    duplicates,
                    importedToQuarantine,
                    legalReferencesFound,
                    rejectedUrls,
                    Duration.ofNanos(System.nanoTime() - startNanos).toMillis(),
                    stats.requests(),
                    stats.retries(),
                    statusCodes,
                    truncated || aborted,
                    List.copyOf(issues));
        }
    }
}
